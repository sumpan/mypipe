package mypipe.producer

import mypipe.api._
import com.typesafe.config.Config
import mypipe.avro.schema.{ AvroSchemaUtils, GenericSchemaRepository }
import mypipe.avro.{ AvroVersionedRecordSerializer }
import mypipe.kafka.KafkaUtil
import org.apache.avro.Schema
import org.apache.avro.generic.{ GenericData }

class KafkaMutationSpecificAvroProducer(config: Config)
    extends KafkaMutationAvroProducer[Short](config) {

  private val schemaRepoClientClassName = config.getString("schema-repo-client")

  override protected val serializer = new AvroVersionedRecordSerializer[InputRecord](schemaRepoClient)
  override protected val schemaRepoClient = Class.forName(schemaRepoClientClassName + "$")
    .getField("MODULE$").get(null)
    .asInstanceOf[GenericSchemaRepository[Short, Schema]]

  override protected def schemaIdToByteArray(s: Short) = Array[Byte](((s & 0xFF00) >> 8).toByte, (s & 0x00FF).toByte)

  override protected def getKafkaTopic(mutation: Mutation[_]): String = KafkaUtil.specificTopic(mutation)

  override protected def avroRecord(mutation: Mutation[_], schema: Schema): GenericData.Record = {

    Mutation.getMagicByte(mutation) match {
      case Mutation.InsertByte ⇒ insertOrDeleteMutationToAvro(mutation.asInstanceOf[SingleValuedMutation], schema)
      case Mutation.UpdateByte ⇒ updateMutationToAvro(mutation.asInstanceOf[UpdateMutation], schema)
      case Mutation.DeleteByte ⇒ insertOrDeleteMutationToAvro(mutation.asInstanceOf[SingleValuedMutation], schema)
      case _ ⇒ {
        logger.error(s"Unexpected mutation type ${mutation.getClass} encountered; retuning empty Avro GenericData.Record(schema=$schema")
        new GenericData.Record(schema)
      }
    }
  }

  /** Given a mutation, returns the "subject" that this mutation's
   *  Schema is registered under in the Avro schema repository.
   *
   *  @param mutation
   *  @return returns "mutationDbName_mutationTableName_mutationType" where mutationType is "insert", "update", or "delete"
   */
  override protected def avroSchemaSubject(mutation: Mutation[_]): String = AvroSchemaUtils.specificSubject(mutation)

  protected def insertOrDeleteMutationToAvro(mutation: SingleValuedMutation, schema: Schema): GenericData.Record = {

    val record = new GenericData.Record(schema)

    mutation.rows.head.columns.foreach(col ⇒ {

      Option(schema.getField(col._1)).map(f ⇒ record.put(f.name(), col._2.value))

    })

    header(record, mutation)
    record
  }

  protected def updateMutationToAvro(mutation: UpdateMutation, schema: Schema): GenericData.Record = {

    val record = new GenericData.Record(schema)

    mutation.rows.head._1.columns.foreach(col ⇒ {
      Option(schema.getField("old_" + col._1)).map(f ⇒ record.put(f.name(), col._2.value))
    })

    mutation.rows.head._2.columns.foreach(col ⇒ {
      Option(schema.getField("new_" + col._1)).map(f ⇒ record.put(f.name(), col._2.value))
    })

    header(record, mutation)
    record
  }
}