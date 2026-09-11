package android.util
open class LruCache<K, V>(private val maximum: Int) {
    private val values = LinkedHashMap<K, V>(16, 0.75f, true)
    open fun sizeOf(key: K, value: V): Int = 1
    fun get(key: K): V? = values[key]
    fun put(key: K, value: V) { values[key] = value; while (values.entries.sumOf { sizeOf(it.key, it.value) } > maximum) values.remove(values.keys.first()) }
    fun remove(key: K): V? = values.remove(key)
    fun evictAll() = values.clear()
}
