package me.kzheart.klib.data.jdbc

import java.io.PrintWriter
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.Properties
import java.util.logging.Logger
import javax.sql.DataSource

/** 驱动延迟加载，连接属性按调用防御性复制，不依赖 DriverManager 的全局注册。 */
class JdbcDataSources private constructor() {
    companion object {
        @JvmStatic
        fun driver(driverClass: String?, loader: ClassLoader?, url: String?, properties: Properties?): DataSource {
            require(!driverClass.isNullOrBlank() && loader != null && !url.isNullOrBlank() && properties != null) {
                "driverClass, loader, url and properties are required"
            }
            return DriverSource(driverClass, loader, url, properties)
        }
    }

    private class DriverSource(
        private val driverClass: String,
        private val loader: ClassLoader,
        private val url: String,
        properties: Properties
    ) : DataSource {
        private val properties = Properties().apply {
            properties.stringPropertyNames().forEach { setProperty(it, properties.getProperty(it)) }
        }
        @Volatile private var driver: Driver? = null
        @Volatile private var writer: PrintWriter? = null

        private fun driver(): Driver {
            driver?.let { return it }
            return synchronized(this) {
                driver ?: try {
                    Class.forName(driverClass, true, loader).asSubclass(Driver::class.java)
                        .getDeclaredConstructor().newInstance().also { driver = it }
                } catch (failure: ReflectiveOperationException) {
                    throw SQLException("JDBC driver unavailable: $driverClass", failure)
                } catch (failure: ClassCastException) {
                    throw SQLException("JDBC driver unavailable: $driverClass", failure)
                }
            }
        }

        @Throws(SQLException::class)
        override fun getConnection(): Connection = connect(Properties().also { it.putAll(properties) })

        @Throws(SQLException::class)
        override fun getConnection(user: String?, password: String?): Connection {
            val copy = Properties().also { it.putAll(properties) }
            if (user == null) copy.remove("user") else copy.setProperty("user", user)
            if (password == null) copy.remove("password") else copy.setProperty("password", password)
            return connect(copy)
        }
        private fun connect(copy: Properties): Connection = driver().connect(url, copy)
            ?: throw SQLException("JDBC driver does not support the configured URL")

        override fun getLogWriter(): PrintWriter? = writer
        override fun setLogWriter(writer: PrintWriter?) { this.writer = writer }
        override fun getLoginTimeout(): Int = 0
        @Throws(SQLException::class)
        override fun setLoginTimeout(seconds: Int) {
            if (seconds != 0) throw SQLFeatureNotSupportedException("Configure the driver's connect timeout")
        }
        override fun getParentLogger(): Logger = Logger.getLogger("me.kzheart.klib.data.jdbc")
        override fun isWrapperFor(type: Class<*>?): Boolean = type?.isInstance(this) == true
        @Throws(SQLException::class)
        override fun <T> unwrap(type: Class<T>?): T {
            if (!isWrapperFor(type)) throw SQLException("Not a wrapper for the requested type")
            return type!!.cast(this)
        }
    }
}
