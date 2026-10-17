package me.kzheart.klib.data.jdbc

import javax.sql.DataSource
import me.kzheart.klib.scope.Disposable
import java.util.concurrent.atomic.AtomicBoolean

/** 数据源与关闭权作为一个资源交付；外部数据源不会被关闭。 */
class JdbcSource private constructor(private val dataSource: DataSource, private val cleanup: Disposable) : Disposable {
    private val closed = AtomicBoolean()
    fun dataSource(): DataSource = dataSource
    override fun dispose() { if (closed.compareAndSet(false, true)) cleanup.dispose() }
    companion object {
        @JvmStatic fun external(source: DataSource): JdbcSource = JdbcSource(source, Disposable { })
        @JvmStatic fun owned(source: DataSource, cleanup: Disposable): JdbcSource = JdbcSource(source, cleanup)
    }
}
