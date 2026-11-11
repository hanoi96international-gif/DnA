package de.ayutv.desktop.input

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.W32APIOptions

/**
 * Bluetooth-Fernbedienungen unter Windows, allen voran die Fire-TV-Fernbedienung.
 *
 * Java/AWT kennt nur Tastatur-Tasten. Zurück, Home, Menü, Spulen und Play/Pause schickt die Fernbedienung aber als
 * HID-„Consumer“-Tasten, die bei AWT nie ankommen. Deshalb liest ein eigener Thread sie direkt über die Windows-
 * Schnittstelle „Raw Input“ mit (unsichtbares Nachrichtenfenster, empfängt auch im Hintergrund) und meldet sie an
 * [onButton]. Tasten, aus denen Windows selbst einen Tastencode macht (Zurück, Home, Play/Pause, Weiter), fängt ein
 * Tastatur-Hook ab, solange DnA-TV im Vordergrund ist – sonst würde Windows dazu den Browser öffnen oder Streaming-App
 * pausieren.
 *
 * Läuft nur unter Windows; anderswo tut [start] nichts. Fehler beim Einrichten schalten nur diese Erkennung ab.
 */
class WindowsRemote(
    /** Taste gedrückt (true) oder losgelassen (false); kommt auf dem Fernbedienungs-Thread. */
    private val onButton: (button: RemoteButton, pressed: Boolean, source: RemoteSource?) -> Unit,
    /** Unbekannte Consumer-Taste (für den Tastentest in den Einstellungen). */
    private val onUnknown: (usage: Int, source: RemoteSource?) -> Unit = { _, _ -> },
    /** Liegt ein DnA-TV-Fenster vorn? Nur dann werden Tasten angenommen. */
    private val isForeground: () -> Boolean,
) {
    @Volatile var running = false; private set
    @Volatile var problem: String? = null; private set

    private var thread: Thread? = null
    @Volatile private var threadId = 0
    @Volatile private var hookActive = false

    // Rückrufe fest halten, sonst räumt der Garbage Collector sie weg, während Windows sie noch aufruft.
    private var windowProc: WinUser.WindowProc? = null
    private var keyboardProc: WinUser.LowLevelKeyboardProc? = null

    /** Je Gerät: gerade gedrückte Consumer-Tasten (zum Erkennen von Loslassen). */
    private val pressed = HashMap<Pointer, Set<Int>>()
    private val sources = HashMap<Pointer, RemoteSource?>()
    private val preparsed = HashMap<Pointer, Memory?>()
    /** Vom Hook gedrückte Tasten (Windows schickt bei gehaltener Taste wiederholt „gedrückt“). */
    private val hookDown = HashSet<Int>()

    fun start() {
        if (running || !isWindows) return
        running = true
        thread = Thread(::loop, "fire-tv-remote").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        val id = threadId
        if (id != 0) runCatching { User32.INSTANCE.PostThreadMessage(id, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0)) }
        running = false
    }

    private fun loop() {
        val user32 = User32.INSTANCE
        var hook: WinUser.HHOOK? = null
        var hwnd: WinDef.HWND? = null
        try {
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            val module = Kernel32.INSTANCE.GetModuleHandle(null)
            val proc = WinUser.WindowProc { h, msg, wParam, lParam ->
                if (msg == WM_INPUT) {
                    runCatching { onRawInput(lParam) }
                }
                user32.DefWindowProc(h, msg, wParam, lParam)
            }
            windowProc = proc
            val wc = WinUser.WNDCLASSEX().apply {
                hInstance = module
                lpfnWndProc = proc
                lpszClassName = CLASS_NAME
            }
            if (user32.RegisterClassEx(wc).toInt() == 0 && Native.getLastError() != ERROR_CLASS_ALREADY_EXISTS) {
                error("Fensterklasse: Fehler ${Native.getLastError()}")
            }
            hwnd = user32.CreateWindowEx(0, CLASS_NAME, "DnA-TV Fernbedienung", 0, 0, 0, 0, 0, HWND_MESSAGE, null, module, null)
                ?: error("Nachrichtenfenster: Fehler ${Native.getLastError()}")

            // Consumer-Tasten (Fernbedienungen) und Tastaturen (nur um zu sehen, woher Pfeile/Enter kommen).
            if (!registerRawInput(hwnd, listOf(0x0C to 0x01))) error("Raw Input: Fehler ${Native.getLastError()}")

            val kbProc = WinUser.LowLevelKeyboardProc { code, wParam, info ->
                if (code >= 0 && onHook(wParam.toInt(), info)) WinDef.LRESULT(1)
                else user32.CallNextHookEx(null, code, wParam, WinDef.LPARAM(Pointer.nativeValue(info.pointer)))
            }
            keyboardProc = kbProc
            hook = user32.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, kbProc, module, 0)
            hookActive = hook != null
            problem = null

            val msg = WinUser.MSG()
            while (user32.GetMessage(msg, null, 0, 0) > 0) {
                user32.TranslateMessage(msg)
                user32.DispatchMessage(msg)
            }
        } catch (t: Throwable) {
            problem = t.message ?: t.toString()
        } finally {
            runCatching { hook?.let { user32.UnhookWindowsHookEx(it) } }
            runCatching { hwnd?.let { user32.DestroyWindow(it) } }
            hookActive = false
            threadId = 0
            running = false
        }
    }

    /** Tastatur-Hook: Medien- und Browser-Tasten abfangen, solange DnA-TV vorn ist. true = verschluckt. */
    private fun onHook(message: Int, info: WinUser.KBDLLHOOKSTRUCT): Boolean {
        val button = RemoteButton.fromWindowsVirtualKey(info.vkCode) ?: return false
        val down = message == WinUser.WM_KEYDOWN || message == WinUser.WM_SYSKEYDOWN
        val up = message == WinUser.WM_KEYUP || message == WinUser.WM_SYSKEYUP
        if (!isForeground()) {
            // Losgelassen wird trotzdem gemeldet, sonst „klemmt“ die Taste, wenn das Fenster dazwischen wechselt.
            if (up && hookDown.remove(info.vkCode)) onButton(button, false, null)
            return false
        }
        if (down) {
            if (hookDown.add(info.vkCode)) onButton(button, true, null)
        } else if (up) {
            hookDown.remove(info.vkCode)
            onButton(button, false, null)
        }
        return true
    }

    private fun onRawInput(lParam: WinDef.LPARAM) {
        val handle = Pointer(lParam.toLong())
        val size = IntByReference()
        if (raw.GetRawInputData(handle, RID_INPUT, null, size, HEADER_SIZE) != 0 || size.value <= 0) return
        val data = Memory(size.value.toLong())
        if (raw.GetRawInputData(handle, RID_INPUT, data, size, HEADER_SIZE) <= 0) return
        if (data.getInt(0) != RIM_TYPEHID) return
        val device = data.getPointer(8) ?: return
        val reportSize = data.getInt(HEADER_SIZE.toLong())
        val count = data.getInt(HEADER_SIZE + 4L)
        if (reportSize <= 0 || count <= 0) return
        val source = sources.getOrPut(device) { describe(device) }
        for (i in 0 until count) {
            val report = data.getByteArray(HEADER_SIZE + 8L + i.toLong() * reportSize, reportSize)
            onReport(device, report, source)
        }
    }

    private fun onReport(device: Pointer, report: ByteArray, source: RemoteSource?) {
        val now = usages(device, report)
        val before = pressed[device].orEmpty()
        pressed[device] = now
        if (!isForeground() && before.isEmpty()) return
        for (usage in now - before) {
            // Tasten, die Windows selbst zu Tastencodes macht, kommen über den Hook (sonst doppelt).
            if (hookActive && usage in RemoteButton.consumerUsagesWithVirtualKey) continue
            val button = RemoteButton.fromConsumerUsage(usage)
            if (button == null) onUnknown(usage, source) else if (isForeground()) onButton(button, true, source)
        }
        for (usage in before - now) {
            if (hookActive && usage in RemoteButton.consumerUsagesWithVirtualKey) continue
            RemoteButton.fromConsumerUsage(usage)?.let { onButton(it, false, source) }
        }
    }

    /** Gedrückte Consumer-Tasten: von Windows zerlegt (HidP_GetUsages), sonst als 16-Bit-Werte gelesen. */
    private fun usages(device: Pointer, report: ByteArray): Set<Int> {
        val parsed = preparsed.getOrPut(device) { preparsedData(device) }
        if (parsed != null) {
            val list = Memory(2L * MAX_USAGES)
            val length = IntByReference(MAX_USAGES)
            val buffer = Memory(report.size.toLong()).apply { write(0, report, 0, report.size) }
            val status = runCatching {
                hid.HidP_GetUsages(HIDP_INPUT, CONSUMER_PAGE.toShort(), 0, list, length, parsed, buffer, report.size)
            }.getOrDefault(-1)
            if (status == HIDP_STATUS_SUCCESS) {
                return (0 until length.value).map { list.getShort(2L * it).toInt() and 0xFFFF }.filter { it != 0 }.toSet()
            }
        }
        return RemoteButton.usagesFromReport(report)
    }

    private fun preparsedData(device: Pointer): Memory? = runCatching {
        val size = IntByReference()
        raw.GetRawInputDeviceInfoW(device, RIDI_PREPARSEDDATA, null, size)
        if (size.value <= 0) return null
        val memory = Memory(size.value.toLong())
        if (raw.GetRawInputDeviceInfoW(device, RIDI_PREPARSEDDATA, memory, size) <= 0) return null
        memory
    }.getOrNull()

    private fun describe(device: Pointer): RemoteSource? = runCatching {
        val chars = IntByReference()
        raw.GetRawInputDeviceInfoW(device, RIDI_DEVICENAME, null, chars)
        if (chars.value <= 0) return null
        val memory = Memory(chars.value.toLong() * 2 + 2)
        if (raw.GetRawInputDeviceInfoW(device, RIDI_DEVICENAME, memory, chars) <= 0) return null
        val name = memory.getWideString(0)
        RemoteSource(name, RemoteButton.isAmazon(name))
    }.getOrNull()

    private fun registerRawInput(hwnd: WinDef.HWND, devices: List<Pair<Int, Int>>): Boolean {
        // RAWINPUTDEVICE: USHORT usUsagePage, USHORT usUsage, DWORD dwFlags, HWND hwndTarget.
        val entry = 8 + Native.POINTER_SIZE
        val memory = Memory(entry.toLong() * devices.size)
        devices.forEachIndexed { i, (page, usage) ->
            val at = i.toLong() * entry
            memory.setShort(at, page.toShort())
            memory.setShort(at + 2, usage.toShort())
            memory.setInt(at + 4, RIDEV_INPUTSINK)
            memory.setPointer(at + 8, hwnd.pointer)
        }
        return raw.RegisterRawInputDevices(memory, devices.size, entry)
    }

    @Suppress("FunctionName")
    private interface RawInput : Library {
        fun RegisterRawInputDevices(devices: Pointer, count: Int, size: Int): Boolean
        fun GetRawInputData(rawInput: Pointer, command: Int, data: Pointer?, size: IntByReference, headerSize: Int): Int
        fun GetRawInputDeviceInfoW(device: Pointer, command: Int, data: Pointer?, size: IntByReference): Int
    }

    @Suppress("FunctionName")
    private interface Hid : Library {
        fun HidP_GetUsages(
            reportType: Int, usagePage: Short, linkCollection: Short, usageList: Pointer, usageLength: IntByReference,
            preparsedData: Pointer, report: Pointer, reportLength: Int,
        ): Int
    }

    private val raw: RawInput by lazy { Native.load("user32", RawInput::class.java, W32APIOptions.DEFAULT_OPTIONS) }
    private val hid: Hid by lazy { Native.load("hid", Hid::class.java) }

    companion object {
        val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        private const val CLASS_NAME = "DnATvRemoteInput"
        private val HWND_MESSAGE = WinDef.HWND(Pointer.createConstant(-3L))
        private const val ERROR_CLASS_ALREADY_EXISTS = 1410
        private const val WM_INPUT = 0x00FF
        private const val RID_INPUT = 0x10000003
        private const val RIM_TYPEHID = 2
        private const val RIDEV_INPUTSINK = 0x00000100
        private const val RIDI_PREPARSEDDATA = 0x20000005
        private const val RIDI_DEVICENAME = 0x20000007
        private const val CONSUMER_PAGE = 0x0C
        private const val HIDP_INPUT = 0
        private const val HIDP_STATUS_SUCCESS = 0x00110000
        private const val MAX_USAGES = 16
        /** RAWINPUTHEADER: dwType, dwSize, hDevice, wParam. */
        private val HEADER_SIZE = 8 + 2 * Native.POINTER_SIZE
    }
}
