package android.net

/**
 * Заглушка `Uri` для JVM-тестов. Конструктор `Uri` виден только из пакета `android.net`, поэтому
 * класс лежит именно здесь. Нужна лишь строка: `toString`, `equals`, `hashCode`.
 */
class TestUri(private val value: String) : Uri() {
    override fun toString() = value
    override fun equals(other: Any?) = other is TestUri && other.value == value
    override fun hashCode() = value.hashCode()
    override fun describeContents() = 0
    override fun writeToParcel(dest: android.os.Parcel, flags: Int) = Unit
    override fun isHierarchical() = true
    override fun isRelative() = false
    override fun getScheme(): String? = null
    override fun getSchemeSpecificPart(): String? = null
    override fun getEncodedSchemeSpecificPart(): String? = null
    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String? = null
    override fun getPort() = -1
    override fun getPath(): String? = null
    override fun getEncodedPath(): String? = null
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): MutableList<String> = mutableListOf()
    override fun getLastPathSegment(): String? = null
    override fun buildUpon(): Builder = throw UnsupportedOperationException()
}
