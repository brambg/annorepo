package nl.knaw.huc.annorepo.resources

import java.io.StringReader
import java.net.URI
import java.util.*
import java.util.concurrent.TimeUnit
import jakarta.annotation.security.PermitAll
import jakarta.json.Json
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType.APPLICATION_JSON
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import jakarta.ws.rs.core.UriBuilder
import com.codahale.metrics.annotation.Timed
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Aggregates.limit
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Filters.eq
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.bson.BsonType
import org.bson.BsonValue
import org.bson.Document
import org.litote.kmongo.findOne
import org.litote.kmongo.getCollection
import org.slf4j.LoggerFactory
import nl.knaw.huc.annorepo.api.*
import nl.knaw.huc.annorepo.api.ARConst.ANNOTATION_FIELD
import nl.knaw.huc.annorepo.api.ARConst.ANNOTATION_NAME_FIELD
import nl.knaw.huc.annorepo.api.ARConst.CONTAINER_NAME_FIELD
import nl.knaw.huc.annorepo.api.ARConst.SECURITY_SCHEME_NAME
import nl.knaw.huc.annorepo.api.ResourcePaths.CONTAINER_SERVICES
import nl.knaw.huc.annorepo.api.ResourcePaths.DISTINCT_FIELD_VALUES
import nl.knaw.huc.annorepo.api.ResourcePaths.FIELDS
import nl.knaw.huc.annorepo.config.AnnoRepoConfiguration
import nl.knaw.huc.annorepo.dao.ContainerUserDAO
import nl.knaw.huc.annorepo.resources.tools.*
import nl.knaw.huc.annorepo.service.JsonLdUtils
import nl.knaw.huc.annorepo.service.UriFactory

@Path(CONTAINER_SERVICES)
@Produces(APPLICATION_JSON)
@PermitAll
@SecurityRequirement(name = SECURITY_SCHEME_NAME)
class ContainerServiceResource(
    private val configuration: AnnoRepoConfiguration,
    client: MongoClient,
    private val containerUserDAO: ContainerUserDAO,
    private val uriFactory: UriFactory,
    private val indexManager: IndexManager
) : AbstractContainerResource(configuration, client, ContainerAccessChecker(containerUserDAO)) {

    private val paginationStage = limit(configuration.pageSize)
    private val aggregateStageGenerator = AggregateStageGenerator(configuration)

    private val queryCache: Cache<String, QueryCacheItem> =
        Caffeine.newBuilder().expireAfterAccess(1, TimeUnit.HOURS).maximumSize(1000).build()
    private val log = LoggerFactory.getLogger(javaClass)

    @Operation(description = "Show the users with access to this container")
    @Timed
    @GET
    @Path("{containerName}/users")
    fun readContainerUsers(
        @PathParam("containerName") containerName: String,
        @Context context: SecurityContext,
    ): Response {
        checkUserHasAdminRightsInThisContainer(context, containerName)

        val users = containerUserDAO.getUsersForContainer(containerName)
        return Response.ok(users).build()
    }

    @Operation(description = "Add users with given role to this container")
    @Timed
    @POST
    @Path("{containerName}/users")
    @Consumes(APPLICATION_JSON)
    fun addContainerUsers(
        @PathParam("containerName") containerName: String,
        @Context context: SecurityContext,
        containerUsers: List<ContainerUserEntry>,
    ): Response {
//        log.info("containerUsers={}", containerUsers)
        checkUserHasAdminRightsInThisContainer(context, containerName)

        for (user in containerUsers) {
            containerUserDAO.removeContainerUser(containerName, user.userName)
            containerUserDAO.addContainerUser(containerName, user.userName, user.role)
        }
        val users = containerUserDAO.getUsersForContainer(containerName)
        return Response.ok(users).build()
    }

    @Operation(description = "Remove the user with the given userName from this container")
    @Timed
    @DELETE
    @Path("{containerName}/users/{userName}")
    fun deleteContainerUser(
        @PathParam("containerName") containerName: String,
        @PathParam("userName") userName: String,
        @Context context: SecurityContext,
    ): Response {
        checkUserHasAdminRightsInThisContainer(context, containerName)
        containerUserDAO.removeContainerUser(containerName, userName)
        return Response.ok().build()
    }

    @Operation(description = "Find annotations in the given container matching the given query")
    @Timed
    @POST
    @Path("{containerName}/search")
    @Consumes(APPLICATION_JSON)
    fun createSearch(
        @PathParam("containerName") containerName: String,
        queryJson: String,
        @Context context: SecurityContext,
    ): Response {
        checkUserHasReadRightsInThisContainer(context, containerName)
        try {
            val queryMap: Map<String, Any?> = Json.createReader(StringReader(queryJson)).readObject().toMap().simplify()
            val aggregateStages = queryMap
                .map { (k, v) -> aggregateStageGenerator.generateStage(k, v!!) }
                .toList()
            val container = mdb.getCollection(containerName)
            val count = container.aggregate(aggregateStages).count()

            val id = UUID.randomUUID().toString()
            queryCache.put(id, QueryCacheItem(queryMap, aggregateStages, count))
            val location = uriFactory.searchURL(containerName, id)
            return Response.created(location)
                .link(uriFactory.searchInfoURL(containerName, id), "info")
                .entity(mapOf("hits" to count))
                .build()
        } catch (e: RuntimeException) {
            e.printStackTrace()
            return Response.status(Response.Status.BAD_REQUEST).build()
        }
    }

    @Operation(description = "Get the given search result page")
    @Timed
    @GET
    @Path("{containerName}/search/{searchId}")
    fun getSearchResultPage(
        @PathParam("containerName") containerName: String,
        @PathParam("searchId") searchId: String,
        @QueryParam("page") page: Int = 0,
        @Context context: SecurityContext,
    ): Response {
        checkUserHasReadRightsInThisContainer(context, containerName)

        val queryCacheItem = getQueryCacheItem(searchId)
        val aggregateStages = queryCacheItem.aggregateStages.toMutableList().apply {
            add(Aggregates.skip(page * configuration.pageSize))
            add(paginationStage)
        }
//        log.debug("aggregateStages=\n  {}", Joiner.on("\n  ").join(aggregateStages))

        val annotations =
            mdb.getCollection(containerName)
                .aggregate(aggregateStages)
                .map { a -> toAnnotationMap(a, containerName) }
                .toList()
        val annotationPage =
            buildAnnotationPage(uriFactory.searchURL(containerName, searchId), annotations, page, queryCacheItem.count)
        return Response.ok(annotationPage).build()
    }

    @Operation(description = "Get information about the given search")
    @Timed
    @GET
    @Path("{containerName}/search/{searchId}/info")
    fun getSearchInfo(
        @PathParam("containerName") containerName: String,
        @PathParam("searchId") searchId: String,
        @Context context: SecurityContext,
    ): Response {
        checkUserHasReadRightsInThisContainer(context, containerName)

        val queryCacheItem = getQueryCacheItem(searchId)
//        val info = mapOf("query" to queryCacheItem.queryMap, "hits" to queryCacheItem.count)
        val query = queryCacheItem.queryMap as Map<String, Any>
        val searchInfo = SearchInfo(
            query = query,
            hits = queryCacheItem.count
        )
        return Response.ok(searchInfo).build()
    }

    @Operation(description = "Get a list of the fields used in the annotations in a container")
    @Timed
    @GET
    @Path("{containerName}/$FIELDS")
    fun getAnnotationFieldsForContainer(
        @PathParam("containerName") containerName: String,
        @Context context: SecurityContext,
    ): Response {
        checkUserHasReadRightsInThisContainer(context, containerName)

        val containerMetadataCollection = mdb.getCollection<ContainerMetadata>(ARConst.CONTAINER_METADATA_COLLECTION)
        val containerMetadata: ContainerMetadata =
            containerMetadataCollection.findOne(eq(CONTAINER_NAME_FIELD, containerName))!!
        return Response.ok(containerMetadata.fieldCounts.toSortedMap()).build()
    }

    @Operation(description = "Get a list of the fields used in the annotations in a container")
    @Timed
    @GET
    @Path("{containerName}/$DISTINCT_FIELD_VALUES/{field}")
    fun getDistinctAnnotationFieldsValuesForContainer(
        @PathParam("containerName") containerName: String,
        @PathParam("field") field: String,
        @Context context: SecurityContext,
    ): Response {
        checkUserHasReadRightsInThisContainer(context, containerName)
        val distinctValues =
            mdb.getCollection(containerName)
                .distinct("$ANNOTATION_FIELD.$field", BsonValue::class.java)
                .map { it.toPrimitive()!! }
                .toList()
        return Response.ok(distinctValues).build()
    }

    @Operation(description = "Get some container metadata")
    @Timed
    @GET
    @Path("{containerName}/metadata")
    fun getMetadataForContainer(
        @PathParam("containerName") containerName: String,
        @Context context: SecurityContext,
    ): Response {
        checkUserHasReadRightsInThisContainer(context, containerName)

        val container = mdb.getCollection(containerName)
        val containerMetadataStore = mdb.getCollection<ContainerMetadata>(ARConst.CONTAINER_METADATA_COLLECTION)
        val meta = containerMetadataStore.findOne { eq(CONTAINER_NAME_FIELD, containerName) }!!

        val metadata = mapOf(
            "id" to uriFactory.containerURL(containerName),
            "label" to meta.label,
            "created" to meta.createdAt,
            "modified" to meta.modifiedAt,
            "size" to container.countDocuments(),
            "indexes" to container.listIndexes(),
        )
        return Response.ok(metadata).build()
    }

    @Operation(description = "List a container's indexes")
    @Timed
    @GET
    @Path("{containerName}/indexes")
    fun getContainerIndexes(
        @PathParam("containerName") containerName: String,
        @Context context: SecurityContext,
    ): Response {
        checkUserHasReadRightsInThisContainer(context, containerName)

        val container = mdb.getCollection(containerName)
        val body = indexData(container, containerName)
        return Response.ok(body).build()
    }

    @Operation(description = "Add an index")
    @Timed
    @PUT
    @Path("{containerName}/indexes/{fieldName}/{indexType}")
    fun addContainerIndex(
        @PathParam("containerName") containerName: String,
        @PathParam("fieldName") fieldNameParam: String,
        @PathParam("indexType") indexTypeParam: String,
        @Context context: SecurityContext,
    ): Response {
        checkUserHasAdminRightsInThisContainer(context, containerName)

        val indexType =
            IndexType.fromString(indexTypeParam) ?: throw BadRequestException(
                "Unknown indexType $indexTypeParam; expected indexTypes: ${
                    IndexType.values()
                        .map { it.name.lowercase() }
                        .joinToString(", ")
                }"
            )
        val indexChore =
            indexManager.startIndexCreation(containerName, fieldNameParam, indexTypeParam, indexType)
        indexManager.getIndexChore(containerName, fieldNameParam, indexTypeParam)
        val location = uriFactory.indexURL(containerName, fieldNameParam, indexTypeParam)
        return Response.created(location)
            .link(uriFactory.indexStatusURL(containerName, fieldNameParam, indexTypeParam), "status")
            .entity(indexChore.status.summary())
            .build()
    }

    @Operation(description = "Get an index definition")
    @Timed
    @GET
    @Path("{containerName}/indexes/{fieldName}/{indexType}")
    fun getContainerIndexDefinition(
        @PathParam("containerName") containerName: String,
        @PathParam("fieldName") fieldName: String,
        @PathParam("indexType") indexType: String,
        @Context context: SecurityContext,
    ): Response {
        checkUserHasAdminRightsInThisContainer(context, containerName)

        val container = mdb.getCollection(containerName)
        val indexConfig =
            getIndexConfig(container, containerName, fieldName, indexType)
        return Response.ok(indexConfig).build()
    }

    @Operation(description = "Get an index status")
    @Timed
    @GET
    @Path("{containerName}/indexes/{fieldName}/{indexType}/status")
    fun getContainerIndexStatus(
        @PathParam("containerName") containerName: String,
        @PathParam("fieldName") fieldName: String,
        @PathParam("indexType") indexType: String,
        @Context context: SecurityContext,
    ): Response {
        checkUserHasAdminRightsInThisContainer(context, containerName)

        val indexChore = indexManager.getIndexChore(containerName, fieldName, indexType) ?: throw NotFoundException()
        return Response.ok(indexChore.status.summary()).build()
    }

    @Operation(description = "Delete a container index")
    @Timed
    @DELETE
    @Path("{containerName}/indexes/{fieldName}/{indexType}")
    fun deleteContainerIndex(
        @PathParam("containerName") containerName: String,
        @PathParam("fieldName") fieldName: String,
        @PathParam("indexType") indexType: String,
        @Context context: SecurityContext,
    ): Response {
        checkUserHasAdminRightsInThisContainer(context, containerName)

        val container = mdb.getCollection(containerName)
        val indexConfig =
            getIndexConfig(container, containerName, fieldName, indexType)
        val indexName = "$ANNOTATION_FIELD.${indexConfig.field}_${indexConfig.type.mongoSuffix}"
        container.dropIndex(indexName)
        return Response.noContent().build()
    }

    @Operation(description = "Upload annotations in batch to a given container")
    @Timed
    @POST
    @Path("{containerName}/annotations-batch")
    fun postAnnotationsBatch(
        @PathParam("containerName") containerName: String,
        annotations: List<HashMap<String, Any>>,
        @Context context: SecurityContext,
    ): Response {
        checkUserHasEditRightsInThisContainer(context, containerName)

        val annotationIdentifiers = mutableListOf<AnnotationIdentifier>()
        val container = mdb.getCollection(containerName)
        for (i in annotations.indices) {
            val annotationName = UUID.randomUUID().toString()
            annotationIdentifiers.add(
                AnnotationIdentifier(
                    containerName = containerName,
                    annotationName = annotationName,
                    etag = makeAnnotationETag(containerName, annotationName).value
                )
            )
        }
        val documents = annotations.mapIndexed { index, annotationMap ->
            val name = annotationIdentifiers[index].annotationName
            Document(ANNOTATION_NAME_FIELD, name).append(ANNOTATION_FIELD, Document(annotationMap))
        }
        container.insertMany(documents)

        val fields = mutableListOf<String>()
        for (annotation in annotations) {
            val annotationJson = ObjectMapper().writeValueAsString(annotation)
            fields.addAll(JsonLdUtils.extractFields(annotationJson).toSet())
        }
        updateFieldCount(containerName, fields, emptySet())
        return Response.ok(annotationIdentifiers).build()
    }

    private fun updateFieldCount(containerName: String, fieldsAdded: List<String>, fieldsDeleted: Set<String>) {
        val containerMetadataCollection = mdb.getCollection<ContainerMetadata>(ARConst.CONTAINER_METADATA_COLLECTION)
        val containerMetadata: ContainerMetadata =
            containerMetadataCollection.findOne(Filters.eq("name", containerName)) ?: return
        val fieldCounts = containerMetadata.fieldCounts.toMutableMap()
        for (field in fieldsAdded.filter { f -> !f.contains("@") }) {
            fieldCounts[field] = fieldCounts.getOrDefault(field, 0) + 1
        }
        for (field in fieldsDeleted.filter { f -> !f.contains("@") }) {
            fieldCounts[field] = fieldCounts.getOrDefault(field, 1) - 1
            if (fieldCounts[field] == 0) {
                fieldCounts.remove(field)
            }
        }
        val newContainerMetadata = containerMetadata.copy(fieldCounts = fieldCounts)
        containerMetadataCollection.replaceOne(Filters.eq("name", containerName), newContainerMetadata)
    }

    private fun getIndexConfig(
        container: MongoCollection<Document>,
        containerName: String,
        fieldName: String,
        indexType: String,
    ): IndexConfig =
        indexData(container, containerName)
            .firstOrNull { it.field == fieldName && it.type == IndexType.fromString(indexType) }
            ?: throw NotFoundException()

    private fun indexData(container: MongoCollection<Document>, containerName: String): List<IndexConfig> =
        container.listIndexes()
            .map { it.toMap().asIndexConfig(containerName) }
            .filterNotNull()
            .toList()

    private fun Map<String, Any>.asIndexConfig(containerName: String): IndexConfig? {
        val name = this["name"].toString()
        val splitPosition = name.lastIndexOf("_")
        val field = name.subSequence(0, splitPosition).toString()
        val typeCode = name.subSequence(startIndex = splitPosition + 1, endIndex = name.lastIndex + 1).toString()
        val prefix = "$ANNOTATION_FIELD."
        return when {
            name.startsWith(prefix) -> {
                val type = when (typeCode) {
                    IndexType.HASHED.mongoSuffix -> IndexType.HASHED
                    IndexType.ASCENDING.mongoSuffix -> IndexType.ASCENDING
                    IndexType.DESCENDING.mongoSuffix -> IndexType.DESCENDING
                    IndexType.TEXT.mongoSuffix -> IndexType.TEXT
                    else -> throw Exception("unexpected index type: $typeCode in $name")
                }
                val fieldName = field.replace(prefix, "")
                IndexConfig(fieldName, type, uriFactory.indexURL(containerName, fieldName, type.name))
            }

            else -> null
        }
    }

    private fun getQueryCacheItem(searchId: String): QueryCacheItem = queryCache.getIfPresent(searchId)
        ?: throw NotFoundException("No search results found for this search id. The search might have expired.")

    private fun buildAnnotationPage(
        searchUri: URI, annotations: AnnotationList, page: Int, total: Int,
    ): AnnotationPage {
        val prevPage = if (page > 0) {
            page - 1
        } else {
            null
        }
        val startIndex = configuration.pageSize * page
        val nextPage = if (startIndex + annotations.size < total) {
            page + 1
        } else {
            null
        }

        return AnnotationPage(
            context = listOf(ANNO_JSONLD_URL),
            id = searchPageUri(searchUri, page),
            partOf = searchUri.toString(),
            startIndex = startIndex,
            items = annotations,
            prev = if (prevPage != null) searchPageUri(searchUri, prevPage) else null,
            next = if (nextPage != null) searchPageUri(searchUri, nextPage) else null
        )
    }

    private fun searchPageUri(searchUri: URI, page: Int) =
        UriBuilder.fromUri(searchUri).queryParam("page", page).build().toString()

    private fun toAnnotationMap(document: Document, containerName: String): Map<String, Any> =
        document[ANNOTATION_FIELD, Document::class.java]
            .toMutableMap()
            .apply<MutableMap<String, Any>> {
                put(
                    "id", uriFactory.annotationURL(containerName, document.getString(ANNOTATION_NAME_FIELD))
                )
            }
}

private fun BsonValue.toPrimitive(): Any? {
    return when (this.bsonType) {
        BsonType.BOOLEAN -> this.asBoolean().value
        BsonType.DATE_TIME -> this.asDateTime().value
        BsonType.DECIMAL128 -> this.asDecimal128().value
        BsonType.DOUBLE -> this.asDouble().value
        BsonType.INT32 -> this.asInt32().value
        BsonType.INT64 -> this.asInt64().value
        BsonType.STRING -> this.asString().value
        BsonType.TIMESTAMP -> this.asTimestamp().value
        else -> this
    }
}


