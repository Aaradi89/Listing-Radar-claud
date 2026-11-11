package com.example.listingradar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Same logic as the Python radar: Binance Futures/Alpha not on Spot + CoinGecko + DEXScreener. */
object Radar {
    class Row(val sym: String, var score: Int, val why: MutableList<String>, val fresh: Boolean)
    class Scan(val rows: List<Row>, val alerts: List<String>, val first: Boolean)
    private class Cg(val mcap: Double, val vol: Double, val chg: Double)

    private const val MIN_ALPHA_VOL = 1_000_000.0
    private const val MIN_DEX_LIQ = 250_000.0
    private const val MIN_DEX_VOL = 1_000_000.0
    private const val MIN_VOL_MCAP = 0.3
    private const val DEX_LOOKUPS = 15
    private const val CG_PAGES = 3

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (listing-radar)")
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) { null }

    private fun spot(): Set<String> = try {
        val a = JSONObject(get("https://api.binance.com/api/v3/exchangeInfo")!!).getJSONArray("symbols")
        (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("status") == "TRADING" }.map { it.getString("baseAsset") }.toSet()
    } catch (e: Exception) { emptySet() }

    private fun futures(): Set<String> = try {
        val a = JSONObject(get("https://fapi.binance.com/fapi/v1/exchangeInfo")!!).getJSONArray("symbols")
        (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("status") == "TRADING" && it.optString("contractType") == "PERPETUAL" }
            .map { it.getString("baseAsset") }.toSet()
    } catch (e: Exception) { emptySet() }

    private fun alpha(): Map<String, Double> = try {
        val url = "https://www.binance.com/bapi/defi/v1/public/wallet-direct/buw/wallet/cex/alpha/all/token/list"
        val a = JSONObject(get(url)!!).optJSONArray("data") ?: JSONArray()
        val m = HashMap<String, Double>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val sym = o.optString("symbol").uppercase()
            if (sym.isEmpty()) continue
            val v = o.optString("volume24h").toDoubleOrNull() ?: 0.0
            m[sym] = maxOf(v, m[sym] ?: 0.0)
        }
        m
    } catch (e: Exception) { emptyMap() }

    private fun announcements(): List<Pair<String, String>> = try {
        val url = "https://www.binance.com/bapi/composite/v1/public/cms/article/list/query?type=1&catalogId=48&pageNo=1&pageSize=20"
        val cats = JSONObject(get(url)!!).getJSONObject("data").getJSONArray("catalogs")
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until cats.length()) {
            val arts = cats.getJSONObject(i).optJSONArray("articles") ?: continue
            for (j in 0 until arts.length()) {
                val a = arts.getJSONObject(j)
                out.add(Pair(a.optString("id", a.optString("code")), a.optString("title")))
            }
        }
        out
    } catch (e: Exception) { emptyList() }

    private fun coingecko(): Map<String, Cg> {
        val out = HashMap<String, Cg>()
        for (p in 1..CG_PAGES) {
            try {
                val url = "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=volume_desc&per_page=250&page=$p"
                val a = JSONArray(get(url) ?: break)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val sym = o.optString("symbol").uppercase()
                    val c = Cg(o.optDouble("market_cap", 0.0), o.optDouble("total_volume", 0.0),
                        o.optDouble("price_change_percentage_24h", 0.0))
                    if ((out[sym]?.mcap ?: -1.0) < c.mcap) out[sym] = c
                }
            } catch (e: Exception) { break }
            Thread.sleep(2500)
        }
        return out
    }

    private fun dex(sym: String): Triple<Double, Double, String>? = try {
        val t = get("https://api.dexscreener.com/latest/dex/search?q=" + URLEncoder.encode(sym, "UTF-8"))!!
        val pairs = JSONObject(t).optJSONArray("pairs") ?: JSONArray()
        var best: Triple<Double, Double, String>? = null
        for (i in 0 until pairs.length()) {
            val p = pairs.getJSONObject(i)
            if (p.optJSONObject("baseToken")?.optString("symbol")?.uppercase() != sym) continue
            val liq = p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
            val vol = p.optJSONObject("volume")?.optDouble("h24", 0.0) ?: 0.0
            if (best == null || liq > best.first) best = Triple(liq, vol, p.optString("chainId", "?"))
        }
        best
    } catch (e: Exception) { null }

    fun runScan(ctx: Context): Scan {
        val sp = ctx.getSharedPreferences("radar", Context.MODE_PRIVATE)
        val first = sp.getBoolean("first", true)
        val prevFut = sp.getStringSet("fut", emptySet())!!
        val prevAlpha = sp.getStringSet("alpha", emptySet())!!
        val seen = sp.getStringSet("seen", emptySet())!!.toMutableSet()

        val spot = spot()
        if (spot.isEmpty()) error("Could not load Binance Spot list (no network, or Binance is blocked in your region)")
        val fut = futures()
        val alpha = alpha()

        val rows = HashMap<String, Row>()
        for (sym in (fut + alpha.keys) - spot) {
            val inF = sym in fut
            val inA = sym in alpha
            val fresh = !first && ((inF && sym !in prevFut) || (inA && sym !in prevAlpha))
            var s = 0
            val why = ArrayList<String>()
            if (inF) { s += 3; why.add("Futures, not Spot") }
            if (inA) { s += 2; why.add("Alpha") }
            val v = alpha[sym] ?: 0.0
            if (v >= MIN_ALPHA_VOL) { s += 1; why.add("Alpha vol \$%,.0f".format(v)) }
            if (fresh) { s += 2; why.add("NEW") }
            rows[sym] = Row(sym, s, why, fresh)
        }

        val cg = coingecko()
        for ((sym, r) in rows) {
            val c = cg[sym] ?: continue
            if (c.mcap > 0 && c.vol / c.mcap >= MIN_VOL_MCAP) { r.score += 1; r.why.add("CG vol/mcap %.2f".format(c.vol / c.mcap)) }
            if (Math.abs(c.chg) >= 10) { r.score += 1; r.why.add("CG 24h %+.0f%%".format(c.chg)) }
        }

        for (sym in rows.keys.sortedByDescending { rows[it]!!.score }.take(DEX_LOOKUPS)) {
            val d = dex(sym)
            Thread.sleep(400)
            if (d == null) continue
            val r = rows[sym]!!
            if (d.first >= MIN_DEX_LIQ) { r.score += 1; r.why.add("DEX liq \$%,.0f (%s)".format(d.first, d.third)) }
            if (d.second >= MIN_DEX_VOL) { r.score += 1; r.why.add("DEX vol \$%,.0f".format(d.second)) }
        }

        val ranked = rows.values.sortedByDescending { it.score }
        val alerts = ArrayList<String>()
        for (r in ranked) if (r.fresh) alerts.add("NEW pipeline token: ${r.sym} (score ${r.score}) - ${r.why.joinToString(", ")}")
        for ((id, title) in announcements()) {
            if (seen.add(id) && !first) alerts.add("Binance announcement: $title")
        }

        sp.edit().putBoolean("first", false).putStringSet("fut", fut).putStringSet("alpha", alpha.keys)
            .putStringSet("seen", seen.toList().takeLast(500).toSet()).apply()
        return Scan(ranked, alerts, first)
    }

    fun notify(ctx: Context, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("radar", "Listing alerts", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(ctx, "radar")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Listing Radar")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).build()
        nm.notify(text.hashCode(), n)
    }
}
