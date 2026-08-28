package h.Hchat.hooks.items.homesidepanel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.alibaba.fastjson2.JSON
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.utils.HLog
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class HomeSidePanelProfile(
    val name: String,
    val wxId: String,
    val signature: String,
    val province: String,
    val city: String
)

internal data class HomeSidePanelWeatherSnapshot(
    val city: String,
    val condition: String,
    val temperature: String,
    val range: String,
    val details: String
) {
    fun displayText(): String = buildString {
        append(city)
        append("  ")
        append(condition)
        append("  ")
        append(temperature)
        if (range.isNotBlank()) append("  $range")
        if (details.isNotBlank()) append("\n$details")
    }
}

internal data class HomeSidePanelHitokotoSnapshot(
    val text: String,
    val source: String
) {
    fun displayText(): String = if (source.isBlank()) text else "$text\n—— $source"
}

internal class HomeSidePanelDataRepository(
    context: Context,
    private val assetContext: Context = context
) {
    private val appContext = context.applicationContext ?: context
    private val prefs = HomeSidePanelSettings.preferences(appContext)
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "Hchat-HomeSidePanel").apply { isDaemon = true }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val activeCalls = linkedSetOf<Call>()

    @Volatile
    private var closed = false

    fun loadProfile(callback: (HomeSidePanelProfile) -> Unit) {
        executor.execute {
            if (closed) return@execute
            val account = WeChatApis.account()
            val name = runCatching { account?.selfName().orEmpty() }.getOrDefault("")
            val wxId = runCatching {
                account?.customWxId().orEmpty().ifBlank { account?.selfWxId().orEmpty() }
            }.getOrDefault("")
            callback(
                HomeSidePanelProfile(
                    name = name.ifBlank { "微信用户" },
                    wxId = wxId,
                    signature = runCatching { account?.signature().orEmpty() }.getOrDefault(""),
                    province = runCatching { account?.province().orEmpty() }.getOrDefault(""),
                    city = runCatching { account?.city().orEmpty() }.getOrDefault("")
                )
            )
        }
    }

    fun refreshWeather(
        profile: HomeSidePanelProfile?,
        force: Boolean,
        callback: (Result<HomeSidePanelWeatherSnapshot>) -> Unit
    ) {
        val cachedAt = prefs.getLong(HomeSidePanelSettings.KEY_WEATHER_CACHE_AT, 0L)
        val cached = prefs.getString(HomeSidePanelSettings.KEY_WEATHER_CACHE, null)
            ?.takeIf(String::isNotBlank)
        if (!force && cached != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            callback(Result.success(parseCachedWeather(cached)))
            return
        }
        executor.execute {
            if (closed) return@execute
            val city = runCatching {
                HomeSidePanelCityIndex(assetContext).resolve(profile?.province.orEmpty(), profile?.city.orEmpty())
            }.onFailure { HLog.w("$TAG 城市索引不可用，回退默认城市: ${it.message}") }
                .getOrDefault(DEFAULT_CITY)
            requestWeather(city, cached, callback)
        }
    }

    fun refreshHitokoto(force: Boolean, callback: (Result<HomeSidePanelHitokotoSnapshot>) -> Unit) {
        val cachedAt = prefs.getLong(HomeSidePanelSettings.KEY_HITOKOTO_CACHE_AT, 0L)
        val cached = prefs.getString(HomeSidePanelSettings.KEY_HITOKOTO_CACHE, null)
            ?.takeIf(String::isNotBlank)
        if (!force && cached != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            callback(Result.success(parseCachedHitokoto(cached)))
            return
        }
        val request = Request.Builder()
            .url("https://v1.hitokoto.cn/?encode=json&charset=utf-8")
            .get()
            .build()
        enqueue(request) { result ->
            result.mapCatching { payload ->
                val json = JSON.parseObject(payload)
                val text = json.getString("hitokoto").orEmpty().trim()
                check(text.isNotBlank()) { "一言内容为空" }
                val author = json.getString("from_who").orEmpty().trim()
                val source = json.getString("from").orEmpty().trim()
                HomeSidePanelHitokotoSnapshot(
                    text = text,
                    source = listOf(author, source).filter(String::isNotBlank).distinct().joinToString(" · ")
                )
            }.onSuccess { snapshot ->
                prefs.edit()
                    .putString(HomeSidePanelSettings.KEY_HITOKOTO_CACHE, encodeHitokoto(snapshot))
                    .putLong(HomeSidePanelSettings.KEY_HITOKOTO_CACHE_AT, System.currentTimeMillis())
                    .apply()
            }.recoverCatching { error ->
                if (cached != null) parseCachedHitokoto(cached) else throw error
            }.let(callback)
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        activeCalls.toList().forEach(Call::cancel)
        activeCalls.clear()
        executor.shutdownNow()
    }

    private fun requestWeather(
        city: WeatherCity,
        cached: String?,
        callback: (Result<HomeSidePanelWeatherSnapshot>) -> Unit
    ) {
        val url = WEATHER_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", "0")
            .addQueryParameter("longitude", "0")
            .addQueryParameter("locationKey", "weathercn:${city.cityNum}")
            .addQueryParameter("sign", WEATHER_SIGN)
            .addQueryParameter("isGlobal", "false")
            .addQueryParameter("locale", "zh_cn")
            .addQueryParameter("days", "5")
            .addQueryParameter("appKey", WEATHER_APP_KEY)
            .build()
        enqueue(Request.Builder().url(url).get().build()) { result ->
            result.mapCatching { payload -> parseWeather(city, payload) }
                .onSuccess { snapshot ->
                    prefs.edit()
                        .putString(HomeSidePanelSettings.KEY_WEATHER_CACHE, encodeWeather(snapshot))
                        .putLong(HomeSidePanelSettings.KEY_WEATHER_CACHE_AT, System.currentTimeMillis())
                        .apply()
                }
                .recoverCatching { error ->
                    if (cached != null) parseCachedWeather(cached) else throw error
                }
                .let(callback)
        }
    }

    private fun enqueue(request: Request, callback: (Result<String>) -> Unit) {
        if (closed) return
        val call = client.newCall(request)
        synchronized(this) {
            if (closed) {
                call.cancel()
                return
            }
            activeCalls += call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@HomeSidePanelDataRepository) { activeCalls -= call }
                if (!closed) callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                synchronized(this@HomeSidePanelDataRepository) { activeCalls -= call }
                response.use {
                    if (closed) return
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("HTTP ${it.code}")))
                    } else {
                        callback(Result.success(it.body?.string() ?: error("响应体为空")))
                    }
                }
            }
        })
    }

    private fun parseWeather(city: WeatherCity, payload: String): HomeSidePanelWeatherSnapshot {
        val root = JSON.parseObject(payload)
        val current = root.getJSONObject("current") ?: error("缺少当前天气")
        val code = current.getString("weather").orEmpty()
        val temperature = current.getJSONObject("temperature")?.getString("value").orEmpty()
        val feelsLike = current.getJSONObject("feelsLike")?.getString("value").orEmpty()
        val humidity = current.getJSONObject("humidity")?.getString("value").orEmpty()
        val wind = current.getJSONObject("wind")?.getJSONObject("speed")?.getString("value").orEmpty()
        val today = root.getJSONObject("forecastDaily")
            ?.getJSONObject("temperature")
            ?.getJSONArray("value")
            ?.getJSONObject(0)
        val high = today?.getString("from").orEmpty()
        val low = today?.getString("to").orEmpty()
        check(temperature.isNotBlank()) { "天气温度为空" }
        return HomeSidePanelWeatherSnapshot(
            city = city.label,
            condition = weatherDescription(code),
            temperature = "${temperature}℃",
            range = if (high.isNotBlank() && low.isNotBlank()) "${low}～${high}℃" else "",
            details = listOfNotNull(
                feelsLike.takeIf(String::isNotBlank)?.let { "体感 ${it}℃" },
                humidity.takeIf(String::isNotBlank)?.let { "湿度 ${it}%" },
                wind.takeIf(String::isNotBlank)?.let { "风速 $it" }
            ).joinToString(" · ")
        )
    }

    private data class WeatherCity(val label: String, val cityNum: String)

    private class HomeSidePanelCityIndex(private val context: Context) {
        fun resolve(province: String, city: String): WeatherCity {
            val wantedProvince = normalize(province)
            val wantedCity = normalize(city)
            if (wantedProvince.isBlank() && wantedCity.isBlank()) return DEFAULT_CITY
            val databaseFile = copyDatabaseAssetOnce()
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { database ->
                database.rawQuery(CITY_QUERY, null).use { cursor ->
                    var best: WeatherCity? = null
                    var bestScore = Int.MIN_VALUE
                    while (cursor.moveToNext()) {
                        val rawName = cursor.getString(0).orEmpty()
                        val cityName = normalize(rawName.substringBefore('.'))
                        val district = normalize(rawName.substringAfter('.', ""))
                        val provinceName = normalize(cursor.getString(2).orEmpty())
                        val score = cityScore(wantedProvince, wantedCity, provinceName, cityName, district)
                        if (score > bestScore) {
                            bestScore = score
                            val label = rawName.replace('.', '·').ifBlank { city.ifBlank { "北京" } }
                            best = WeatherCity(label, cursor.getString(1).orEmpty())
                        }
                    }
                    if (best != null && bestScore > 0 && best.cityNum.isNotBlank()) return best
                }
            }
            return DEFAULT_CITY
        }

        private fun copyDatabaseAssetOnce(): File {
            val directory = File(context.noBackupFilesDir, "home_side_panel").apply { mkdirs() }
            val target = File(directory, "xiaomi_weather.db")
            if (!target.isFile || target.length() == 0L) {
                context.assets.open("home_side_panel/xiaomi_weather.db").use { input ->
                    target.outputStream().use(input::copyTo)
                }
            }
            return target
        }

        private fun cityScore(
            wantedProvince: String,
            wantedCity: String,
            province: String,
            city: String,
            district: String
        ): Int {
            var score = 0
            if (wantedProvince.isNotBlank()) {
                if (province == wantedProvince) score += 10 else if (province.contains(wantedProvince)) score += 5
            }
            if (wantedCity.isNotBlank()) {
                when {
                    city + district == wantedCity -> score += 30
                    district == wantedCity -> score += 25
                    city == wantedCity -> score += 20
                    city.contains(wantedCity) || wantedCity.contains(city) -> score += 8
                }
            }
            return score
        }
    }

    private companion object {
        const val TAG = "[Hchat:HomeSidePanelData]"
        const val CACHE_TTL_MS = 30L * 60L * 1000L
        const val WEATHER_ENDPOINT = "https://weatherapi.market.xiaomi.com/wtr-v3/weather/all"
        const val WEATHER_SIGN = "zUFJoAR2ZVrDy1vF3D07"
        const val WEATHER_APP_KEY = "weather20151024"
        val DEFAULT_CITY = WeatherCity("北京", "101010100")
        val REGION_SUFFIXES = listOf(
            "特别行政区", "维吾尔自治区", "壮族自治区", "回族自治区",
            "自治区", "省", "市", "区", "县"
        )
        const val CITY_QUERY = """
            SELECT c.name, c.city_num, p.name AS province
            FROM citys c
            LEFT JOIN provinces p ON p._id = c.province_id + 1
            ORDER BY c._id
        """

        fun normalize(value: String): String {
            var result = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
            REGION_SUFFIXES.forEach { result = result.replace(it, "") }
            return result
        }

        fun weatherDescription(code: String): String = when (code.toIntOrNull()) {
            0 -> "晴"
            1 -> "多云"
            2 -> "阴"
            3 -> "阵雨"
            4 -> "雷阵雨"
            5 -> "冰雹"
            6, 19 -> "雨夹雪"
            7 -> "小雨"
            8, 21, 22 -> "中雨"
            9, 23 -> "大雨"
            10, 11, 12, 24, 25 -> "暴雨"
            13 -> "阵雪"
            14 -> "小雪"
            15, 26 -> "中雪"
            16, 27 -> "大雪"
            17, 28, 34 -> "暴雪"
            18, 35 -> "雾"
            20, 29, 30, 31 -> "沙尘"
            32 -> "飑"
            33 -> "龙卷风"
            53 -> "霾"
            else -> "天气"
        }

        fun encodeWeather(value: HomeSidePanelWeatherSnapshot): String = listOf(
            value.city, value.condition, value.temperature, value.range, value.details
        ).joinToString("\u0001")

        fun parseCachedWeather(value: String): HomeSidePanelWeatherSnapshot {
            val parts = value.split('\u0001')
            return HomeSidePanelWeatherSnapshot(
                parts.getOrElse(0) { "北京" },
                parts.getOrElse(1) { "天气" },
                parts.getOrElse(2) { "--℃" },
                parts.getOrElse(3) { "" },
                parts.getOrElse(4) { "" }
            )
        }

        fun encodeHitokoto(value: HomeSidePanelHitokotoSnapshot): String =
            value.text + "\u0001" + value.source

        fun parseCachedHitokoto(value: String): HomeSidePanelHitokotoSnapshot {
            val parts = value.split('\u0001', limit = 2)
            return HomeSidePanelHitokotoSnapshot(parts.firstOrNull().orEmpty(), parts.getOrElse(1) { "" })
        }
    }
}
