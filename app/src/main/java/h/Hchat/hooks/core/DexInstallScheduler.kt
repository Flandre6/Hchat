package h.Hchat.hooks.core

import android.os.Handler
import android.os.Looper
import h.Hchat.utils.HLog
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 统一调度依赖 DexKit 的 Hook 安装。
 *
 * 功能层只返回本次是否真正安装成功；缓存缺失、DexKit 暂时空结果或 Hook 失败时，
 * 这里负责去重和有限重试，避免用户靠多次强停微信碰运气。
 */
object DexInstallScheduler {
    enum class Stage(internal val level: Int) {
        EARLY(0),
        BRIDGE(1),
        WARMUP(2)
    }

    private const val TAG = "[Hchat:DexInstall]"
    private const val MAX_RETRY_ATTEMPTS = 6
    private const val MAX_RETRY_DELAY_MS = 60_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = ConcurrentHashMap<String, State>()
    private val readyLevel = AtomicInteger(0)
    private val dexKitExecutionLock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hchat-DexInstall").apply {
            isDaemon = true
        }
    }

    fun schedule(
        key: String,
        label: String = key,
        stage: Stage = Stage.BRIDGE,
        priority: Int = 0,
        installer: () -> Boolean
    ) {
        if (key.isBlank()) return
        val state = states.computeIfAbsent(key) { State(label) }
        state.label = label
        state.stage = stage
        state.priority = priority
        state.installer = installer
        if (!isReady(stage)) return
        if (!state.installed.get() && !state.running.get() && !state.retryScheduled.get() &&
            state.retryIndex.get() >= MAX_RETRY_ATTEMPTS
        ) {
            state.retryIndex.set(0)
        }
        runNow(key, state)
    }

    @JvmStatic
    fun scheduleTask(key: String, label: String, stage: Stage, priority: Int, installer: Callable<Boolean>) {
        schedule(key, label, stage, priority) {
            installer.call() == true
        }
    }

    @JvmStatic
    fun markDexBridgeReady() {
        advanceReady(Stage.BRIDGE)
    }

    @JvmStatic
    fun markDexReady() {
        markDexWarmupReady()
    }

    @JvmStatic
    fun markDexWarmupReady() {
        advanceReady(Stage.WARMUP)
    }

    @JvmStatic
    fun runDexKitTask(task: Runnable) {
        synchronized(dexKitExecutionLock) {
            task.run()
        }
    }

    private fun advanceReady(stage: Stage) {
        while (true) {
            val current = readyLevel.get()
            if (current >= stage.level) break
            if (readyLevel.compareAndSet(current, stage.level)) break
        }
        runReadyStates()
    }

    fun markInstalled(key: String) {
        states.computeIfAbsent(key) { State(key) }.installed.set(true)
    }

    private fun runNow(key: String, state: State) {
        if (!isReady(state.stage)) return
        if (state.installed.get()) return
        val installer = state.installer ?: return
        if (!state.running.compareAndSet(false, true)) return
        executor.execute {
            var ok = false
            try {
                ok = synchronized(dexKitExecutionLock) {
                    installer()
                }
            } catch (e: Throwable) {
                HLog.e("$TAG ${state.label} 安装异常: ${e.message}", e)
            } finally {
                if (ok) {
                    state.installed.set(true)
                }
                state.running.set(false)
            }
            if (!ok) scheduleRetry(key, state)
        }
    }

    private fun runReadyStates() {
        states.entries
            .asSequence()
            .filter { isReady(it.value.stage) }
            .sortedWith(
                compareBy<Map.Entry<String, State>> { it.value.stage.level }
                    .thenBy { it.value.priority }
                    .thenBy { it.key }
            )
            .forEach { (key, state) -> runNow(key, state) }
    }

    private fun scheduleRetry(key: String, state: State) {
        if (state.installed.get()) return
        if (!state.retryScheduled.compareAndSet(false, true)) return
        val index = state.retryIndex.getAndIncrement()
        if (index >= MAX_RETRY_ATTEMPTS) {
            state.retryScheduled.set(false)
            HLog.e("$TAG ${state.label} 多次安装失败，停止本轮重试")
            return
        }
        mainHandler.postDelayed({
            state.retryScheduled.set(false)
            if (isReady(state.stage)) runNow(key, state)
        }, retryDelayMs(index))
    }

    private fun retryDelayMs(attempt: Int): Long {
        if (attempt <= 0) return 1_000L
        if (attempt == 1) return 3_000L
        return minOf(MAX_RETRY_DELAY_MS, 3_000L * (1L shl (attempt - 1)))
    }

    private fun isReady(stage: Stage): Boolean {
        return readyLevel.get() >= stage.level
    }

    private class State(@Volatile var label: String) {
        @Volatile var stage: Stage = Stage.BRIDGE
        @Volatile var priority: Int = 0
        @Volatile var installer: (() -> Boolean)? = null
        val installed = AtomicBoolean(false)
        val running = AtomicBoolean(false)
        val retryScheduled = AtomicBoolean(false)
        val retryIndex = AtomicInteger(0)
    }
}
