package com.freefjay.localshare.desktop.util

import kotlinx.coroutines.asContextElement
import com.freefjay.localshare.desktop.logger
import com.freefjay.localshare.desktop.model.SqliteMaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.Double
import java.lang.Float
import java.lang.Long
import java.lang.reflect.Type
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Types
import java.util.Date
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType


@Retention
annotation class Column (
    val value: String = "",
    val type: String = "",
    val isPrimaryKey: Boolean = false
)

data class TableInfo (
    val name: String?,
    val columns: List<ColumnInfo>?
)

data class ColumnInfo (
    val name: String?,
    val type: String?,
    val isPrimaryKey: Boolean?,
    val javaType: Type? = null,
    val kProperty: KProperty<*>? = null
)

fun getSqliteType(clazz: Type?): String? {
    return when (clazz) {
        String::class.java -> "TEXT"
        java.lang.Integer::class.java -> "INTEGER"
        java.lang.Long::class.java -> "INTEGER"
        java.lang.Float::class.java -> "REAL"
        java.lang.Double::class.java -> "REAL"
        BigDecimal::class.java -> "NUMERIC"
        ByteArray::class.java -> "BLOB"
        Date::class.java -> "TEXT"
        java.lang.Boolean::class.java -> "INTEGER"
        else -> null
    }
}

class ConnInfo(
    val transactionName: String? = null,
    val connection: Connection,
    val parent: ConnInfo? = null
)

val dbPath = "${System.getProperty("user.home")}/.localShare/share.db"

suspend fun initDbPath() {
    withContext(Dispatchers.IO) {
        val file = File(dbPath)
        val parent = file.parentFile
        if (!parent.exists()) {
            parent.mkdirs()
        }
    }
}

fun getDbConn(): Connection = DriverManager.getConnection("jdbc:sqlite:${dbPath}")

fun runSql(block: (conn: Connection) -> Unit) {
    val conn = localTransactionManager.get()
    if (conn != null) {
        block(conn.connection)
    } else {
        block(getDbConn())
    }
}

val localTransactionManager = ThreadLocal<ConnInfo?>()

suspend fun <T> transaction(name: String? = null, block: suspend () -> T): T {
    val parentConnInfo = localTransactionManager.get()
    val conn = ConnInfo(
        transactionName = name,
        connection = parentConnInfo?.connection ?: getDbConn(),
        parent = parentConnInfo
    )
    logger.info("${name?.let { "-${it}-" } ?: ""}连接信息: ${conn.transactionName}, ${conn.parent?.transactionName}")
    val isSub = threadLocalQueueFlag.get() == true
    return taskQueue.execute(context = localTransactionManager.asContextElement(conn)) {
        conn.connection.use {
            try {
                logger.info("------------------- 开始${if (isSub) "子" else "" }事务${name?.let { s -> "-${s}-" } ?: ""}(${Thread.currentThread().id}) ------------------------")
                it.autoCommit = false
                val result = block()
                it.commit()
                result
            } catch (e : Exception) {
                it.rollback()
                throw e
            } finally {
                it.autoCommit = true
                logger.info("------------------- 结束${if (isSub) "子" else "" }事务${name?.let { s -> "-${s}-" } ?: ""}(${Thread.currentThread().id}) ------------------------")
            }
        }
    }
}

suspend fun queryMap(sql: String?, args: Array<String>? = null): List<Map<String, Any?>> {
    return suspendCoroutine { continuation ->
        runSql { conn ->
            val statement = conn.prepareStatement(sql)
            statement.use {
                val resultSet = statement.executeQuery()
                resultSet.use {
                    val list = mutableListOf<MutableMap<String, Any?>>()
                    val metaData = resultSet.metaData
                    while (resultSet.next()) {
                        val map = mutableMapOf<String, Any?>()
                        for (index in 1 until metaData.columnCount + 1) {
                            val name = metaData.getColumnName(index)
                            if (map.containsKey(name)) {
                                continue
                            }
                            val type = metaData.getColumnType(index)
                            var value: Any? = null
                            when(type) {
                                Types.BLOB -> {
                                    value = resultSet.getBlob(index)
                                }

                                Types.FLOAT -> {
                                    value = resultSet.getFloat(index)
                                }

                                Types.INTEGER -> {
                                    value = resultSet.getInt(index)
                                }

                                Types.NULL -> {
                                    value = null
                                }

                                Types.VARCHAR -> {
                                    value = resultSet.getString(index)
                                }
                            }
                            map[name] = value
                        }
                        list.add(map)
                    }
                    continuation.resume(list)
                }
            }
        }
    }
}

suspend inline fun <reified T> queryList(sql: String?, args: Array<String>? = null): List<T> {
    logger.info("queryList: ${sql}")
    return suspendCoroutine { continuation ->
        runSql { conn ->
            val statement = conn.prepareStatement(sql)
            statement.use {
                val resultSet = statement.executeQuery()
                resultSet.use {
                    val list = mutableListOf<T>()
                    val clazz = T::class
                    val constructor = clazz.constructors.first()
                    while (resultSet.next()) {
                        list.add(constructor.call(*(constructor.parameters.map { parameter ->
                            val colName = parameter.name?.toUnderCase()
                            if (colName != null) {
                                val index = resultSet.findColumn(colName)
                                when (parameter.type.javaType) {
                                    String::class.java -> resultSet.getString(index)
                                    Integer::class.java -> resultSet.getInt(index)
                                    Long::class.java -> resultSet.getLong(index)
                                    Float::class.java -> resultSet.getFloat(index)
                                    Double::class.java -> resultSet.getDouble(index)
                                    BigDecimal::class.java -> if (resultSet.getString(index) != null) BigDecimal(resultSet.getString(index)) else null
                                    ByteArray::class.java -> resultSet.getBlob(index)
                                    Date::class.java -> resultSet.getString(index)?.toDate()
                                    java.lang.Boolean::class.java -> (resultSet.getInt(index)).let { if (it == 0) false else if (it == 1) true else null }
                                    else -> null
                                }
                            } else {
                                null
                            }
                        }.toTypedArray())))
                    }
                    continuation.resume(list)
                }
            }
        }
    }
}

suspend inline fun <reified T> queryOne(sql: String?, args: Array<String>? = null): T? {
    logger.info("queryOne: $sql")
    val list = queryList<T>(sql = sql, args = args)
    return list.firstOrNull()
}

inline fun <reified T : Any> getValue(columnInfo: ColumnInfo, data: T): Any {
    val value = columnInfo.kProperty?.getter?.call(data) ?: return "null"
    if (columnInfo.type == "TEXT") {
        return if (columnInfo.javaType == Date::class.java) {
            "\"${(value as Date).format()}\""
        } else {
            "\"${value}\""
        }
    }
    if (columnInfo.javaType == Boolean::class.java) {
        return (value as Boolean).let { if (it) 1 else 0 }
    }
    return value
}

suspend inline fun <reified T : Any> save(data: T) {
    return suspendCoroutine { continuation ->
        runSql { conn ->
            val clazz = T::class
            val tableInfo = resolveTableInfo(clazz)
            val tableName = tableInfo.name
            val columns = tableInfo.columns
            val primaryKeyColumn = columns?.find { it.isPrimaryKey == true }
            val primaryValue = if (primaryKeyColumn != null) primaryKeyColumn.kProperty?.getter?.call(data) else null

            if (primaryKeyColumn == null || primaryValue == null) {
                val sql = """
                    insert into ${tableName} (${columns?.joinToString(", ") { item -> item.name?: "" }}) values (
                    ${columns?.map{ item -> getValue(item, data) }?.joinToString(", ")})
                """.trimIndent()
                logger.info("save: $sql")
                val sqlStatement = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                sqlStatement.executeUpdate()
                val rs = sqlStatement.generatedKeys
                val id = if (rs.next()) {
                    rs.getLong(1)
                } else {
                    null
                }
                logger.info("primaryKeyColumn: $primaryKeyColumn")
                if ((primaryKeyColumn?.kProperty is KMutableProperty) && primaryKeyColumn.javaType == Long::class.java && id != -1L) {
                    primaryKeyColumn.kProperty.setter.call(data, id)
                }
            } else {
                val sql = """
                    update ${tableName} set ${
                    columns.joinToString(", ") { "${it.name} = ${getValue(it, data)}" }
                } where ${primaryKeyColumn.name} = ${getValue(primaryKeyColumn, data)}
                """.trimIndent()
                logger.info("update: ${sql}")
                val sqlStatement = conn.prepareStatement(sql)
                sqlStatement.executeUpdate()
            }
            continuation.resume(Unit)
        }
    }
}

suspend inline fun <reified T : Any> delete(id: Any?): Int? {
    return suspendCoroutine { continuation ->
        runSql { conn ->
            continuation.resume(
                if (id != null) {
                    val tableInfo = resolveTableInfo(T::class)
                    val tableName = tableInfo.name
                    val columns = tableInfo.columns
                    val primaryKeyColumn = columns?.find { it.isPrimaryKey == true }
                    val sql = "delete from $tableName where ${primaryKeyColumn?.name} = ${
                        if (primaryKeyColumn?.type == "TEXT") {
                            "\"${id}\""
                        } else {
                            id.toString()
                        }
                    }"
                    logger.info("delete: $sql")
                    val sqlStatement = conn.prepareStatement(sql)
                    sqlStatement.executeUpdate()
                } else 0
            )
        }
    }
}

suspend fun executeSql(sql: String?, bindArgs: Array<Any>? = null) {
    suspendCoroutine { continuation ->
        runSql { conn ->
            val sqlStatement = conn.prepareStatement(sql)
            sqlStatement.executeUpdate()
            continuation.resume(Unit)
        }
    }
}

fun <T : Any> resolveTableInfo(kClazz: KClass<T>): TableInfo {
    val propertyMap = mutableMapOf<String, KProperty<*>>()
    kClazz.declaredMemberProperties.forEach {
        propertyMap[it.name] = it
    }
    return TableInfo(
        name = kClazz.simpleName?.toFirstLower()?.toUnderCase(),
        columns = kClazz.primaryConstructor?.parameters?.map {
            val column = it.findAnnotation<Column>()
            ColumnInfo(
                name = if (column?.value?.isNotBlank() == true) column.value else it.name?.toUnderCase(),
                type = if (column?.type?.isNotBlank() == true) column.type else getSqliteType(it.type.javaType) ?: throw RuntimeException("不支持的类型：${kClazz.simpleName} ${it.type.javaType}"),
                isPrimaryKey = column?.isPrimaryKey == true,
                kProperty = propertyMap[it.name],
                javaType = it.type.javaType
            )
        }
    )
}

suspend inline fun <T : Any> updateTableStruct(kClazz: KClass<T>) {
    val tableInfo = resolveTableInfo(kClazz)
    logger.info("tableInfo: ${tableInfo}")
    val oldTable = queryList<SqliteMaster>("select * from sqlite_master where type = 'table' and name = '${tableInfo.name}'").firstOrNull()
    logger.info("旧表: ${oldTable}")
    if (oldTable == null) {
        val sql = """
                    create table ${tableInfo.name}(${tableInfo.columns?.joinToString(", ") { "${it.name} ${it.type}${if (it.isPrimaryKey == true) " primary key AUTOINCREMENT " else ""}" }})
                """.trimIndent()
        logger.info("建表sql: $sql")
        executeSql(sql)
        logger.info("建表${tableInfo.name}成功")
    } else {
        val pattern = Pattern.compile("${tableInfo.name}\\s*(.*)")
        val match = oldTable.sql?.let { pattern.matcher(it) }
        if (match?.find() == true) {
            var columnBody = match.group(1)
            columnBody = columnBody?.substring(1, columnBody.length - 1)
            val oldColumns = columnBody?.split(",")?.map {
                val columnAry = it.trim().split("\\s+".toRegex())
                val name = columnAry[0]
                val type = columnAry[1]
                val isPrimaryKey = it.lowercase().contains("primary")
                return@map ColumnInfo(name=name, type = type, isPrimaryKey = isPrimaryKey)
            }
            logger.info("oldColumns: ${oldColumns}")
            val oldColumnMap = mutableMapOf<String?, ColumnInfo>()
            oldColumns?.forEach {
                oldColumnMap[it.name] = it
            }
            val addColumns = mutableListOf<ColumnInfo>()
            tableInfo.columns?.forEach {
                val oldColumn = oldColumnMap[it.name]
                if (oldColumn == null) {
                    addColumns.add(it)
                } else {
                    if (
                        it.type?.trim()?.lowercase() != oldColumn.type?.trim()?.lowercase()
                        || it.isPrimaryKey != oldColumn.isPrimaryKey
                    ) {
                        logger.warn("列不一样，$it, $oldColumn", )
                    }
                }
            }
            addColumns.forEach {
                val sql = "alter table ${tableInfo.name} add column ${it.name} ${it.type} ${if (it.isPrimaryKey == true) "primary key" else ""}"
                logger.info("新增列: $sql")
                executeSql(sql)
                logger.info("新增列成功")
            }
        }
    }
}
