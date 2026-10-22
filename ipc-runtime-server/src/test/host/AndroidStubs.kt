package android.os

/** Host doubles only. No Android Binder behavior or device timing is simulated. */
object Binder {
    private val caller = ThreadLocal.withInitial { 1000 }
    @JvmStatic fun getCallingUid(): Int = caller.get()
    fun setCallingUid(uid: Int) { caller.set(uid) }
}

interface IBinder {
    fun interface DeathRecipient { fun binderDied() }
    fun linkToDeath(recipient: DeathRecipient, flags: Int)
    fun unlinkToDeath(recipient: DeathRecipient, flags: Int): Boolean
    fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean
    companion object { const val FLAG_ONEWAY = 1 }
}

class Parcel private constructor() {
    val values = mutableListOf<Any?>()
    fun writeLong(value: Long) { values += value }
    fun writeInt(value: Int) { values += value }
    fun writeString(value: String?) { values += value }
    fun recycle() {}
    companion object { @JvmStatic fun obtain() = Parcel() }
}
