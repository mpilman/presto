package ch.ethz.tell.presto

import ch.ethz.tell.Field
import ch.ethz.tell.Field.FieldType.*
import ch.ethz.tell.Table
import ch.ethz.tell.Transaction
import com.facebook.presto.spi.*
import com.facebook.presto.spi.connector.ConnectorMetadata
import com.facebook.presto.spi.predicate.TupleDomain
import com.facebook.presto.spi.type.*
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonGetter
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.*

object TableCache {
    private var tables: ImmutableMap<String, Table>? = null
    private var tableIds: ImmutableMap<Long, Table>? = null

    private fun init(transaction: Transaction) {
        if (tables == null) {
            synchronized(this) {
                if (tables == null) {
                    val builder = ImmutableMap.builder<String, Table>()
                    val idBuilder = ImmutableMap.builder<Long, Table>()
                    transaction.tables.forEach {
                        builder.put(it.tableName, it)
                        idBuilder.put(it.tableId, it)
                    }
                    tables = builder.build()
                    tableIds = idBuilder.build()
                }
            }
        }
    }

    fun openTable(transaction: Transaction, name: String): Table {
        init(transaction)
        val res = tables!![name] ?: throw RuntimeException("table $name does not exist")
        return res
    }

    fun openTable(transaction: Transaction, id: Long): Table {
        init(transaction)
        val res = tableIds!![id] ?: throw RuntimeException("table $id does not exist")
        return res
    }

    fun getTableNames(transaction: Transaction): ImmutableList<String> {
        init(transaction)
        val builder = ImmutableList.builder<String>()
        tables!!.forEach { builder.add(it.key) }
        return builder.build()
    }

    fun getTables(transaction: Transaction): ImmutableMap<String, Table> {
        init(transaction)
        return tables!!
    }
}

object PrimaryKeyColumn {
    val column = ColumnMetadata("__key", BigintType.BIGINT, true);
}

object DefaultSchema {
    val name = "default"
}

class TellTableHandle(val table: Table,
                      @get:com.fasterxml.jackson.annotation.JsonGetter val transactionId: Long) : ConnectorTableHandle {
    @JsonCreator
    constructor(@JsonProperty("tableId") tableId: Long, @JsonProperty("transactionId") transactionId: Long)
    : this(TableCache.openTable(Transaction.startTransaction(transactionId, TellConnection.clientManager),
            tableId), transactionId)

    @JsonGetter
    fun getTableId(): Long {
        return table.tableId
    }
}

class TellTableLayoutHandle : ConnectorTableLayoutHandle {
    @get:JsonGetter
    val table: TellTableHandle
    @get:JsonGetter
    val domain: TupleDomain<ColumnHandle>

    @JsonCreator
    constructor(@JsonProperty("table") table: TellTableHandle, @JsonProperty("domain") domain: TupleDomain<ColumnHandle>) {
        this.table = table
        this.domain = domain
    }
}

open class TellColumnHandleBase : ColumnHandle, JsonDeserializer<TellColumnHandleBase>() {
    override fun deserialize(parser: JsonParser?, p1: DeserializationContext?): TellColumnHandleBase? {
        val node = parser?.readValueAsTree<JsonNode>() ?: throw RuntimeException("parser is null")
        val typename = node.get("typeName")
        if (typename.toString() == "primary")
            return PrimaryKeyColumnHandle()
        else {
            val field = node.get("fieldString").binaryValue()
            val bi = ByteArrayInputStream(field)
            val si = ObjectInputStream(bi)
            val res = TellColumnHandle(si.readObject() as Field)
            si.close()
            bi.close()
            return res
        }
    }
}

class PrimaryKeyColumnHandle : TellColumnHandleBase() {
    @JsonProperty
    fun getTypeName(): String {
        return "primary"
    }
}

class TellColumnHandle(val field: Field) : TellColumnHandleBase() {
    @JsonProperty
    fun getTypeName(): String {
        return "column"
    }

    @JsonProperty
    fun getFieldString(): ByteArray {
        val bo = ByteArrayOutputStream()
        val so = ObjectOutputStream(bo)
        so.writeObject(field)
        so.flush()
        val res = bo.toByteArray()
        bo.close()
        so.close()
        return res
    }
}

fun Field.prestoType(): Type {
    return when (fieldType) {
        null -> throw RuntimeException("NULL")
        NOTYPE -> throw RuntimeException("NOTYPE")
        NULLTYPE -> throw RuntimeException("NULL")
        SMALLINT, INT, BIGINT -> BigintType.BIGINT
        FLOAT, DOUBLE -> DoubleType.DOUBLE
        TEXT -> VarcharType.VARCHAR
        BLOB -> VarbinaryType.VARBINARY
    }
}

class TellMetadata(val transaction: Transaction) : ConnectorMetadata {
    override fun listSchemaNames(session: ConnectorSession?): MutableList<String>? {
        return ImmutableList.of(DefaultSchema.name)
    }

    override fun getTableHandle(session: ConnectorSession?, tableName: SchemaTableName?): ConnectorTableHandle? {
        if (tableName != null && tableName.schemaName == DefaultSchema.name) {
            return TellTableHandle(TableCache.openTable(transaction, tableName.tableName), transaction.transactionId)
        }
        throw RuntimeException("tableName is null")
    }

    override fun getTableLayouts(session: ConnectorSession?,
                                 table: ConnectorTableHandle?,
                                 constraint: Constraint<ColumnHandle>,
                                 desiredColumns: Optional<MutableSet<ColumnHandle>>): MutableList<ConnectorTableLayoutResult>? {
        if (table !is TellTableHandle) {
            val typename = table?.javaClass?.name ?: "null"
            throw RuntimeException("table is not from tell (type is $typename)")
        }
        val columns = if (desiredColumns.isPresent) ImmutableList.copyOf(
                desiredColumns.get()) else ImmutableList.copyOf(getColumnHandles(session, table).values)
        val layout = ConnectorTableLayout(TellTableLayoutHandle(table, constraint.summary))
        return ImmutableList.of(ConnectorTableLayoutResult(layout, TupleDomain.none()))
    }

    override fun getTableLayout(session: ConnectorSession?,
                                handle: ConnectorTableLayoutHandle?): ConnectorTableLayout? {
        return ConnectorTableLayout(handle)
    }

    override fun getTableMetadata(session: ConnectorSession?, table: ConnectorTableHandle?): ConnectorTableMetadata? {
        if (table !is TellTableHandle) throw RuntimeException("table is not from tell")
        val columns = getColumnHandles(session, table)
        val builder = ImmutableList.builder<ColumnMetadata>()
        columns.forEach {
            builder.add(getColumnMetadata(session, table, it.value))
        }
        return ConnectorTableMetadata(SchemaTableName(DefaultSchema.name, table.table.tableName), builder.build())
    }

    override fun listTables(session: ConnectorSession?, schemaNameOrNull: String?): MutableList<SchemaTableName>? {
        if (schemaNameOrNull != null && schemaNameOrNull != DefaultSchema.name) {
            throw RuntimeException("Schemas not supported by tell but $schemaNameOrNull was given")
        }
        return ImmutableList.copyOf(
                TableCache.getTableNames(transaction).map { SchemaTableName(DefaultSchema.name, it) })
    }

    override fun getColumnHandles(session: ConnectorSession?,
                                  tableHandle: ConnectorTableHandle?): MutableMap<String, ColumnHandle> {
        if (tableHandle !is TellTableHandle) {
            throw RuntimeException("tableHandle is not from Tell")
        }
        val builder = ImmutableMap.builder<String, ColumnHandle>()
        builder.put("__key", PrimaryKeyColumnHandle())
        tableHandle.table.schema.fieldNames.forEach {
            builder.put(it, TellColumnHandle(tableHandle.table.schema.getFieldByName(it)))
        }
        return builder.build()
    }

    private fun getMetadata(name: String, field: Field): ColumnMetadata {
        return ColumnMetadata(name, field.prestoType(), false)
    }

    override fun getColumnMetadata(session: ConnectorSession?,
                                   tableHandle: ConnectorTableHandle?,
                                   columnHandle: ColumnHandle): ColumnMetadata? {
        if (columnHandle is PrimaryKeyColumnHandle) {
            return ColumnMetadata("__key", BigintType.BIGINT, true)
        }
        if (columnHandle !is TellColumnHandle) {
            throw RuntimeException("columnHandle is not from Tell")
        }
        return getMetadata(columnHandle.field.fieldName, columnHandle.field)
    }

    override fun listTableColumns(session: ConnectorSession?,
                                  prefix: SchemaTablePrefix?): MutableMap<SchemaTableName, MutableList<ColumnMetadata>>? {
        if (prefix == null) {
            throw RuntimeException("prefix is null")
        }
        val builder = ImmutableMap.builder<SchemaTableName, MutableList<ColumnMetadata>>()
        val tables = TableCache.getTables(transaction)
        tables.forEach {
            if (it.key.startsWith(prefix.tableName)) {
                var b = ImmutableList.builder<ColumnMetadata>()
                b.add(ColumnMetadata("__key", BigintType.BIGINT, true))
                val schema = it.value.schema
                schema.fieldNames.forEach {
                    val field = schema.getFieldByName(it)
                    b.add(getMetadata(field.fieldName, field))
                }
                builder.put(SchemaTableName(DefaultSchema.name, it.key), b.build())
            }
        }
        return builder.build()
    }
}
