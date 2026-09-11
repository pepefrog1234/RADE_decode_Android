package yakumo2683.RADEdecode.network

/** Native CI-V endpoint, replaceable by a fake in the UDP protocol tests. */
internal interface IcomPty {
    fun open(): String?
    fun write(data: ByteArray, len: Int): Int
    fun read(timeoutMs: Int): ByteArray?
    fun close()
}

internal object NativeIcomPty : IcomPty {
    init { System.loadLibrary("rade_jni") }

    // Keep the JNI declarations on their original class: the C symbols use it.
    override fun open() = IcomNetworkManager.nativeIcomPtyOpen()
    override fun write(data: ByteArray, len: Int) = IcomNetworkManager.nativeIcomPtyWrite(data, len)
    override fun read(timeoutMs: Int) = IcomNetworkManager.nativeIcomPtyRead(timeoutMs)
    override fun close() = IcomNetworkManager.nativeIcomPtyClose()
}
