package android.os

/** Host-only value buffer. It does not simulate Android Parcel or Binder behavior. */
class Parcel private constructor() {
    private val values = mutableListOf<String?>()
    private var position = 0
    fun writeString(value: String?) { values.add(value) }
    fun readString(): String? = values[position++]
    fun recycle() {}
    companion object { @JvmStatic fun obtain() = Parcel() }
}
