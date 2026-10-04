package io.github.adrianss31.lowfreqhunter

import android.app.Application
import io.github.adrianss31.lowfreqhunter.data.*
import io.github.adrianss31.lowfreqhunter.service.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[29,35])
class LanDashboardServiceTest {
    @Test fun stoppingRecorderLeavesServerReadableAndDisableClosesSocket() {
        val port=ServerSocket(0).use { it.localPort }
        val controller=Robolectric.buildService(LanDashboardService::class.java).create()
        val service=controller.get()
        try {
            val cfg=AppSettings(lan=LanCfg(true,port,"known-test"))
            service.configure(cfg)
            fun read(): JsonObject {
                val conn=URL("http://127.0.0.1:$port/api/state?k=known-test").openConnection()
                conn.connectTimeout=1500;conn.readTimeout=1500
                return conn.getInputStream().use { Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject }
            }
            MonitorBus.state.value=MonitorBus.state.value.copy(running=true,mode="rec")
            assertTrue(read()["running"]!!.jsonPrimitive.boolean)
            MonitorBus.state.value=MonitorBus.state.value.copy(running=false,mode="")
            assertFalse(read()["running"]!!.jsonPrimitive.boolean)
            service.configure(cfg) // repeated recording start does not bind a second socket
            assertFalse(read()["running"]!!.jsonPrimitive.boolean)
            service.configure(cfg.copy(lan=cfg.lan.copy(enabled=false)))
            assertTrue(runCatching { read() }.isFailure)
            assertNull(MonitorBus.state.value.lanUrl)
        } finally { controller.destroy();MonitorBus.state.value=MonitorBus.State();MonitorBus.spectrum.value=null }
    }
    @Test fun lateSettingsCannotReopenDestroyedService() {
        val port=ServerSocket(0).use { it.localPort }
        val controller=Robolectric.buildService(LanDashboardService::class.java).create();val service=controller.get()
        val cfg=AppSettings(lan=LanCfg(true,port,"destroy-test"))
        service.configure(cfg);controller.destroy()
        service.configure(cfg)
        assertTrue(runCatching { URL("http://127.0.0.1:$port/api/state?k=destroy-test").openConnection().apply { connectTimeout=300;readTimeout=300 }.getInputStream().close() }.isFailure)
        MonitorBus.state.value=MonitorBus.State()
    }
}
