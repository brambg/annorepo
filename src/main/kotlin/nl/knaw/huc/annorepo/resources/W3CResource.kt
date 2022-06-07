package nl.knaw.huc.annorepo.resources

import com.codahale.metrics.annotation.Timed
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoDatabase
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import nl.knaw.huc.annorepo.api.ARConst.ANNOTATION_MEDIA_TYPE
import nl.knaw.huc.annorepo.api.AnnotationData
import nl.knaw.huc.annorepo.api.ResourcePaths
import nl.knaw.huc.annorepo.config.AnnoRepoConfiguration
import nl.knaw.huc.annorepo.service.UriFactory
import org.bson.Document
import org.eclipse.jetty.util.ajax.JSON
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Instant
import java.util.*
import javax.ws.rs.DELETE
import javax.ws.rs.GET
import javax.ws.rs.HeaderParam
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

@Api(ResourcePaths.W3C)
@Path(ResourcePaths.W3C)
@Produces(MediaType.APPLICATION_JSON)
class W3CResource(
    private val client: MongoClient,
    configuration: AnnoRepoConfiguration,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val uriFactory = UriFactory(configuration)
    private val mdb = mongoDatabase()

    @ApiOperation(value = "Create an Annotation Container")
    @Timed
    @POST
    fun createContainer(
        @HeaderParam("slug") slug: String?,
    ): Response {
        var name = slug ?: UUID.randomUUID().toString()
        if (mdb.listCollectionNames().contains(name)) {
            log.debug("A container with the suggested name $name already exists, generating a new name.")
            name = UUID.randomUUID().toString()
        }
        mdb.createCollection(name)
        val containerData = getContainerData(name)
        val uri = uriFactory.containerURL(name)
        return Response.created(uri).entity(containerData).build()
    }

    @ApiOperation(value = "Get an Annotation Container")
    @Timed
    @GET
    @Path("{containerName}")
    fun readContainer(@PathParam("containerName") containerName: String): Response {
        log.debug("read Container $containerName")
        val container = getContainerData(containerName)
        return if (container != null) {
            Response.ok(container).build()
        } else {
            Response.status(Response.Status.NOT_FOUND).entity("Container '$containerName' not found").build()
        }

    }

    @ApiOperation(value = "Delete an empty Annotation Container")
    @Timed
    @DELETE
    @Path("{containerName}")
    fun deleteContainer(@PathParam("containerName") containerName: String): Response {
        log.debug("delete Container $containerName")
        val containerData = getContainerData(containerName)
        return if (containerData.annotationCount == 0L) {
            mdb.getCollection(containerName).drop()
            Response.noContent().build()
        } else {
            Response.status(Response.Status.BAD_REQUEST)
                .entity("Container $containerName is not empty, all annotations need to be removed from this container first.")
                .build()
        }
    }

    @ApiOperation(value = "Create an Annotation")
    @Timed
    @POST
    @Path("{containerName}")
    fun createAnnotation(
        @HeaderParam("slug") slug: String?,
        @PathParam("containerName") containerName: String,
        annotationJson: String
    ): Response {
//        log.debug("annotation=\n$annotationJson")
        var name = slug ?: UUID.randomUUID().toString()
        val uri = uriFactory.annotationURL(containerName, name)
        val container = mdb.getCollection(containerName)
        val existingAnnotationDocument = container.find(Document("annotation_name", name)).first()
        if (existingAnnotationDocument != null) {
            log.warn("An annotation with the suggested name $name already exists in container $containerName, generating a new name.")
            name = UUID.randomUUID().toString()
        }
        val annotationDocument = Document.parse(annotationJson)
        val doc = Document("annotation_name", name).append("annotation", annotationDocument)
        val r = container.insertOne(doc).insertedId?.asObjectId()?.value
        val annotationData = AnnotationData(
            r!!.timestamp.toLong(),
            name,
            doc.getEmbedded(listOf("annotation"), Document::class.java).toJson(),
            Date.from(Instant.now()),
            Date.from(Instant.now())
        )
        val entity = withInsertedId(annotationData, containerName, name)
        return Response.created(uri).entity(entity).build()
    }

    @ApiOperation(value = "Get an Annotation")
    @Timed
    @GET
    @Path("{containerName}/{annotationName}")
    @Produces(ANNOTATION_MEDIA_TYPE)
    fun readAnnotation(
        @PathParam("containerName") containerName: String, @PathParam("annotationName") annotationName: String
    ): Response {
        log.debug("read annotation $annotationName in container $containerName")
        val container = mdb.getCollection(containerName)
        val annotationDocument = container.find(Document("annotation_name", annotationName)).first()
        return if (annotationDocument != null) {
            val annotationData = AnnotationData(
                0L,
                annotationName,
                annotationDocument.toJson(),
                Date.from(Instant.now()),
                Date.from(Instant.now())
            )
            val entity = withInsertedId(annotationData, containerName, annotationName)
            Response.ok(entity).header("Last-Modified", annotationData.modified).build()
        } else Response.status(Response.Status.NOT_FOUND).build()
    }

    @ApiOperation(value = "Delete an Annotation")
    @Timed
    @DELETE
    @Path("{containerName}/{annotationName}")
    fun deleteAnnotation(
        @PathParam("containerName") containerName: String, @PathParam("annotationName") annotationName: String
    ) {
        log.debug("delete annotation $annotationName in container $containerName")
        val container = mdb.getCollection(containerName)
        container.findOneAndDelete(Document("annotation_name", annotationName))
    }

    private fun withInsertedId(
        annotationData: AnnotationData, containerName: String, annotationName: String
    ): Any? {
        val content = annotationData.content
        var jo = JSON.parse(content)
        if (jo is HashMap<*, *>) {
            jo = jo.toMutableMap()
            jo["id"] = uriFactory.annotationURL(containerName, annotationName)
        }
        return jo
    }

    private fun getContainerData(name: String): MongoCollectionData {
        val collection = mdb.getCollection(name)
        val uri = uriFactory.containerURL(name)
        val count = collection.countDocuments()
        return MongoCollectionData(name, uri, count)
    }

    private fun mongoDatabase(): MongoDatabase = client.getDatabase("annorepo")

}

data class MongoCollectionData(val name: String, val id: URI, val annotationCount: Long)
