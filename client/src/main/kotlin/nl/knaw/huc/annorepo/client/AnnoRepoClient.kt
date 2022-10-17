package nl.knaw.huc.annorepo.client

import arrow.core.Either
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import nl.knaw.huc.annorepo.api.AnnotationIdentifier
import nl.knaw.huc.annorepo.api.IndexType
import nl.knaw.huc.annorepo.api.ResourcePaths.ABOUT
import nl.knaw.huc.annorepo.api.ResourcePaths.ADMIN
import nl.knaw.huc.annorepo.api.ResourcePaths.BATCH
import nl.knaw.huc.annorepo.api.ResourcePaths.FIELDS
import nl.knaw.huc.annorepo.api.ResourcePaths.INDEXES
import nl.knaw.huc.annorepo.api.ResourcePaths.INFO
import nl.knaw.huc.annorepo.api.ResourcePaths.METADATA
import nl.knaw.huc.annorepo.api.ResourcePaths.SEARCH
import nl.knaw.huc.annorepo.api.ResourcePaths.SERVICES
import nl.knaw.huc.annorepo.api.ResourcePaths.USERS
import nl.knaw.huc.annorepo.api.ResourcePaths.W3C
import nl.knaw.huc.annorepo.api.UserEntry
import nl.knaw.huc.annorepo.client.ARResult.AddIndexResult
import nl.knaw.huc.annorepo.client.ARResult.AddUsersResult
import nl.knaw.huc.annorepo.client.ARResult.AnnotationFieldInfoResult
import nl.knaw.huc.annorepo.client.ARResult.BatchUploadResult
import nl.knaw.huc.annorepo.client.ARResult.CreateAnnotationResult
import nl.knaw.huc.annorepo.client.ARResult.CreateContainerResult
import nl.knaw.huc.annorepo.client.ARResult.CreateQueryResult
import nl.knaw.huc.annorepo.client.ARResult.DeleteAnnotationResult
import nl.knaw.huc.annorepo.client.ARResult.DeleteContainerResult
import nl.knaw.huc.annorepo.client.ARResult.DeleteIndexResult
import nl.knaw.huc.annorepo.client.ARResult.DeleteUserResult
import nl.knaw.huc.annorepo.client.ARResult.GetAboutResult
import nl.knaw.huc.annorepo.client.ARResult.GetContainerMetadataResult
import nl.knaw.huc.annorepo.client.ARResult.GetContainerResult
import nl.knaw.huc.annorepo.client.ARResult.GetQueryInfoResult
import nl.knaw.huc.annorepo.client.ARResult.ListIndexesResult
import nl.knaw.huc.annorepo.client.ARResult.QueryResultPageResult
import nl.knaw.huc.annorepo.client.ARResult.UsersResult
import nl.knaw.huc.annorepo.client.RequestError.ConnectionError
import nl.knaw.huc.annorepo.util.extractVersion
import org.glassfish.jersey.client.filter.EncodingFilter
import org.glassfish.jersey.message.GZipEncoder
import org.slf4j.LoggerFactory
import java.net.URI
import javax.ws.rs.client.ClientBuilder
import javax.ws.rs.client.Entity
import javax.ws.rs.client.Invocation
import javax.ws.rs.client.WebTarget
import javax.ws.rs.core.Response

private const val IF_MATCH = "if-match"

/**
 * Client to access annorepo servers.
 *
 * @constructor
 * @param serverURI the server *URI*
 * @param apiKey the api-key for authentication (optional)
 * @param userAgent the string to identify this client in the User-Agent header (optional)
 *
 */
class AnnoRepoClient @JvmOverloads constructor(
    serverURI: URI, val apiKey: String? = null, private val userAgent: String? = null
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webTarget: WebTarget = ClientBuilder.newClient().apply {
        register(GZipEncoder::class.java)
        register(EncodingFilter::class.java)
    }.target(serverURI)

    private val oMapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    var serverVersion: String? = null
    var serverNeedsAuthentication: Boolean? = null

    init {
        log.info("checking annorepo server at $serverURI ...")
        getAbout().bimap(
            { e ->
                log.error("error: {}", e)
                throw RuntimeException("Unable to connect to annorepo server")
            },
            { getAboutResult ->
                val aboutInfo = getAboutResult.aboutInfo
                serverVersion = aboutInfo.version
                serverNeedsAuthentication = aboutInfo.withAuthentication
                log.info("$serverURI runs version $serverVersion ; needs authentication: $serverNeedsAuthentication")
            }
        )
    }

    /**
     * Get some information about the server
     *
     * @return
     */
    fun getAbout(): Either<RequestError, GetAboutResult> = doGet(
        request = webTarget.path(ABOUT).request(),
        responseHandlers = mapOf(Response.Status.OK to { response: Response ->
            val json = response.readEntityAsJsonString();
            Either.Right(GetAboutResult(response, oMapper.readValue(json)))
        })
    )

    /**
     * Create an annotation container
     *
     * @param preferredName
     * @param label
     * @return
     */
    fun createContainer(
        preferredName: String? = null,
        label: String = "A container for web annotations"
    ): Either<RequestError, CreateContainerResult> {
        var request = webTarget.path(W3C).request()
        if (preferredName != null) {
            request = request.header("slug", preferredName)
        }
        return doPost(
            request = request,
            entity = Entity.json(containerSpecs(label)),
            responseHandlers = mapOf(
                Response.Status.CREATED to { response ->
                    val location = response.location()!!
                    val containerName = extractContainerName(location.toString())
                    val eTag = response.eTag() ?: ""
                    Either.Right(
                        CreateContainerResult(
                            response = response,
                            location = location,
                            containerName = containerName,
                            eTag = eTag
                        )
                    )
                })
        )
    }

    /**
     * Get an annotation container
     *
     * @param containerName
     * @return
     */
    fun getContainer(
        containerName: String
    ): Either<RequestError, GetContainerResult> =
        doGet(
            request = webTarget.path(W3C).path(containerName).request(),
            responseHandlers = mapOf(
                Response.Status.OK to { response ->
                    Either.Right(
                        GetContainerResult(
                            response = response,
                            eTag = response.entityTag.value
                        )
                    )
                })
        )

    /**
     * Get annotation container metadata
     *
     * @param containerName
     * @return
     */
    fun getContainerMetadata(
        containerName: String
    ): Either<RequestError, GetContainerMetadataResult> =
        doGet(
            request = webTarget.path(SERVICES).path(containerName).path(METADATA).request(),
            responseHandlers = mapOf(
                Response.Status.OK to { response ->
                    val json = response.readEntityAsJsonString()
                    val metadata: Map<String, Any> = oMapper.readValue(json)
                    Either.Right(
                        GetContainerMetadataResult(
                            response = response,
                            metadata = metadata
                        )
                    )
                })
        )

    /**
     * Delete an annotation container
     *
     * @param containerName
     * @param eTag
     * @return
     */
    fun deleteContainer(containerName: String, eTag: String): Either<RequestError, DeleteContainerResult> =
        doDelete(
            request = webTarget.path(W3C).path(containerName).request().header(IF_MATCH, eTag),
            responseHandlers = mapOf(
                Response.Status.NO_CONTENT to { response ->
                    Either.Right(
                        DeleteContainerResult(
                            response = response
                        )
                    )
                })
        )

    /**
     * Create annotation
     *
     * @param containerName
     * @param annotation
     * @return
     */
    fun createAnnotation(
        containerName: String,
        annotation: Map<String, Any>
    ): Either<RequestError, CreateAnnotationResult> =
        doPost(
            request = webTarget.path(W3C).path(containerName).request(),
            entity = Entity.json(annotation),
            responseHandlers = mapOf(
                Response.Status.CREATED to { response ->
                    val location = response.location()!!
                    val annotationName = extractAnnotationName(location.toString())
                    val eTag = response.eTag() ?: ""
                    Either.Right(
                        CreateAnnotationResult(
                            response = response,
                            location = location,
                            containerName = containerName,
                            annotationName = annotationName,
                            eTag = eTag
                        )
                    )
                })
        )

    /**
     * Update annotation
     *
     * @param containerName
     * @param annotationName
     * @param eTag
     * @param annotation
     * @return
     */
    fun updateAnnotation(
        containerName: String, annotationName: String, eTag: String, annotation: Map<String, Any>
    ): Either<RequestError, CreateAnnotationResult> =
        doPut(
            request = webTarget.path(W3C).path(containerName).path(annotationName).request().header(IF_MATCH, eTag),
            entity = Entity.json(annotation),
            responseHandlers = mapOf(Response.Status.OK to { response ->
                val location = response.location()!!
                val newEtag = response.eTag() ?: ""
                Either.Right(
                    CreateAnnotationResult(
                        response = response,
                        location = location,
                        containerName = containerName,
                        annotationName = annotationName,
                        eTag = newEtag
                    )
                )
            })
        )

    /**
     * Delete annotation
     *
     * @param containerName
     * @param annotationName
     * @param eTag
     * @return
     */
    fun deleteAnnotation(
        containerName: String,
        annotationName: String,
        eTag: String
    ): Either<RequestError, DeleteAnnotationResult> =
        doDelete(
            request = webTarget.path(W3C).path(containerName).path(annotationName)
                .request()
                .header(IF_MATCH, eTag),
            responseHandlers = mapOf(
                Response.Status.NO_CONTENT to { response ->
                    Either.Right(
                        DeleteAnnotationResult(
                            response
                        )
                    )
                })
        )

    /**
     * Get field info
     *
     * @param containerName
     * @return
     */
    fun getFieldInfo(containerName: String): Either<RequestError, AnnotationFieldInfoResult> =
        doGet(
            request = webTarget.path(SERVICES).path(containerName).path(FIELDS).request(),
            responseHandlers = mapOf(
                Response.Status.OK to { response ->
                    val json = response.readEntityAsJsonString()
                    Either.Right(
                        AnnotationFieldInfoResult(
                            response = response,
                            fieldInfo = oMapper.readValue(json)
                        )
                    )
                })
        )

    /**
     * Batch upload
     *
     * @param containerName
     * @param annotations
     * @return
     */
    fun batchUpload(
        containerName: String, annotations: List<Map<String, Any>>
    ): Either<RequestError, BatchUploadResult> =
        doPost(
            request = webTarget.path(BATCH).path(containerName).path("annotations").request(),
            entity = Entity.json(annotations),
            responseHandlers = mapOf(
                Response.Status.OK to { response ->
                    val entityJson: String = response.readEntityAsJsonString()
                    val annotationData: List<AnnotationIdentifier> = oMapper.readValue(entityJson)
                    Either.Right(
                        BatchUploadResult(response, annotationData)
                    )
                })
        )

    /**
     * Create query
     *
     * @param containerName
     * @param query
     * @return
     */
    fun createQuery(containerName: String, query: Map<String, Any>): Either<RequestError, CreateQueryResult> =
        doPost(
            request = webTarget.path(SERVICES).path(containerName).path(SEARCH).request(),
            entity = Entity.json(query),
            responseHandlers = mapOf(
                Response.Status.CREATED to { response ->
                    val location = response.location
                    val queryId = location.rawPath.split("/").last()
                    Either.Right(
                        CreateQueryResult(response = response, location = location, queryId = queryId)
                    )
                })
        )

    /**
     * Get query result
     *
     * @param containerName
     * @param queryId
     * @param page
     * @return
     */
    fun getQueryResultPage(
        containerName: String,
        queryId: String,
        page: Int
    ): Either<RequestError, QueryResultPageResult> =
        doGet(
            request = webTarget.path(SERVICES).path(containerName).path(SEARCH).path(queryId)
                .queryParam("page", page)
                .request(),
            responseHandlers = mapOf(
                Response.Status.OK to { response ->
                    Either.Right(
                        QueryResultPageResult(
                            response = response
                        )
                    )
                }
            )
        )

    /**
     * Get query info
     *
     * @param containerName
     * @param queryId
     * @return
     */
    fun getQueryInfo(containerName: String, queryId: String): Either<RequestError, GetQueryInfoResult> =
        doGet(
            request = webTarget.path(SERVICES).path(containerName).path(SEARCH).path(queryId).path(INFO)
                .request(),
            responseHandlers = mapOf(
                Response.Status.OK to { response ->
                    Either.Right(GetQueryInfoResult(response))
                }
            )
        )

    /**
     * Add index
     *
     * @param containerName
     * @param fieldName
     * @param indexType
     * @return
     */
    fun addIndex(containerName: String, fieldName: String, indexType: IndexType): Either<RequestError, AddIndexResult> =
        doPut(
            request = webTarget.path(SERVICES).path(containerName).path(INDEXES).path(fieldName)
                .path(indexType.name).request(),
            entity = Entity.json(emptyMap<String, Any>()),
            responseHandlers = mapOf(
                Response.Status.CREATED to { response ->
                    Either.Right(AddIndexResult(response = response))
                })
        )

    /**
     * List indexes
     *
     * @param containerName
     * @return
     */
    fun listIndexes(containerName: String): Either<RequestError, ListIndexesResult> =
        doGet(
            request = webTarget.path(SERVICES).path(containerName).path(INDEXES).request(),
            responseHandlers = mapOf(Response.Status.OK to { response ->
                val jsonString = response.readEntityAsJsonString()
                val indexes: List<Map<String, Any>> = oMapper.readValue(jsonString)
                Either.Right(
                    ListIndexesResult(
                        response = response,
                        indexes = indexes
                    )
                )
            })
        )

    /**
     * Delete index
     *
     * @param containerName the name of the container
     * @param fieldName the name of the indexed field
     * @param indexType the type of index
     * @return
     */
    fun deleteIndex(
        containerName: String,
        fieldName: String,
        indexType: IndexType
    ): Either<RequestError, DeleteIndexResult> =
        doDelete(
            request = webTarget.path(SERVICES).path(containerName).path(INDEXES).path(fieldName).path(indexType.name)
                .request(),
            responseHandlers = mapOf(
                Response.Status.NO_CONTENT to { response ->
                    Either.Right(DeleteIndexResult(response))
                })
        )

    /**
     * Get users
     *
     * @return
     */
    fun getUsers(): Either<RequestError, UsersResult> =
        doGet(
            request = webTarget.path(ADMIN).path(USERS).request(),
            responseHandlers = mapOf(
                Response.Status.OK to { response ->
                    val json = response.readEntityAsJsonString()
                    val userEntryList = oMapper.readValue(json, object : TypeReference<List<UserEntry>>() {})
                    Either.Right(
                        UsersResult(
                            response = response,
                            userEntries = userEntryList
                        )
                    )
                })
        )

    /**
     * Add users
     *
     * @param users
     * @return
     */
    fun addUsers(users: List<UserEntry>): Either<RequestError, AddUsersResult> =
        doPost(
            request = webTarget.path(ADMIN).path(USERS).request(),
            entity = Entity.json(users),
            responseHandlers = mapOf(
                Response.Status.OK to { response ->
                    Either.Right(
                        AddUsersResult(response)
                    )
                })
        )

    /**
     * Delete user
     *
     * @param userName
     * @return
     */
    fun deleteUser(userName: String): Either<RequestError, DeleteUserResult> =
        doDelete(
            request = webTarget.path(ADMIN).path(USERS).path(userName).request(),
            responseHandlers = mapOf(
                Response.Status.NO_CONTENT to { response ->
                    Either.Right(
                        DeleteUserResult(response)
                    )
                })
        )

    // private functions
    private fun <T> doGet(
        request: Invocation.Builder, responseHandlers: ResponseHandlerMap<T>
    ): Either<RequestError, T> = doRequest {
        request.withHeaders().get().processResponseWith(responseHandlers)
    }

    private fun <T> doPost(
        request: Invocation.Builder, entity: Entity<*>, responseHandlers: ResponseHandlerMap<T>
    ): Either<RequestError, T> = doRequest {
        request.withHeaders().post(entity).processResponseWith(responseHandlers)
    }

    private fun <T> doPut(
        request: Invocation.Builder, entity: Entity<*>, responseHandlers: ResponseHandlerMap<T>
    ): Either<RequestError, T> = doRequest {
        request.withHeaders().put(entity).processResponseWith(responseHandlers)
    }

    private fun <T> doDelete(
        request: Invocation.Builder, responseHandlers: ResponseHandlerMap<T>
    ): Either<RequestError, T> = doRequest {
        request.withHeaders().delete().processResponseWith(responseHandlers)
    }

    private fun <T> Response.processResponseWith(
        responseHandlers: Map<Response.Status, (Response) -> Either<RequestError, T>>
    ): Either<RequestError, T> {
        val handlerIdx = responseHandlers.entries.associate { it.key.statusCode to it.value }
        return when (status) {
            in handlerIdx.keys -> handlerIdx[status]!!.invoke(this)
            Response.Status.UNAUTHORIZED.statusCode -> unauthorizedResponse(this)
            else -> unexpectedResponse(this)
        }
    }

    private fun unauthorizedResponse(response: Response): Either.Left<RequestError> = Either.Left(
        RequestError.NotAuthorized(
            message = "Not authorized to make this call; check your apiKey",
            headers = response.headers,
            responseString = response.readEntityAsJsonString()
        )
    )

    private fun unexpectedResponse(response: Response): Either.Left<RequestError> = Either.Left(
        RequestError.UnexpectedResponse(
            message = "Unexpected status: ${response.status}",
            headers = response.headers,
            responseString = response.readEntityAsJsonString()
        )
    )

    private fun Response.readEntityAsJsonString(): String =
        readEntity(String::class.java) ?: ""

    private fun extractContainerName(location: String): String {
        val parts = location.split("/")
        return parts[parts.size - 2]
    }

    private fun extractAnnotationName(location: String): String {
        val parts = location.split("/")
        return parts[parts.size - 1]
    }

    private fun Response.location(): URI? {
        val firstHeader = firstHeader("location") ?: return null
        return URI.create(firstHeader)
    }

    private fun Response.eTag(): String? = firstHeader("etag")

    private fun <R> doRequest(requestHandler: () -> Either<RequestError, R>): Either<RequestError, R> = try {
        requestHandler()
    } catch (e: Exception) {
        Either.Left(ConnectionError(e.message ?: "Connection Error"))
    }

    private fun Response.firstHeader(key: String): String? = if (headers.containsKey(key)) {
        val locations: MutableList<Any> = headers[key]!!
        locations[0].toString()
    } else {
        null
    }

    private fun Invocation.Builder.withHeaders(): Invocation.Builder {
        val libUA = "${AnnoRepoClient::class.java.name}/${getVersion() ?: ""}"
        val ua = if (userAgent == null) {
            libUA
        } else {
            "$userAgent ( using $libUA )"
        }
        var builder = header("User-Agent", ua).header("Accept-Encoding", "gzip").header("Content-Encoding", "gzip")

        if (serverNeedsAuthentication != null && serverNeedsAuthentication!!) {
            builder = builder.header("Authorization", "Bearer $apiKey")
        }
        return builder
    }

    private fun containerSpecs(label: String) = mapOf(
        "@context" to listOf(
            "http://www.w3.org/ns/anno.jsonld", "http://www.w3.org/ns/ldp.jsonld"
        ), "type" to listOf(
            "BasicContainer", "AnnotationCollection"
        ), "label" to label
    )

    private fun getVersion(): String? = this.javaClass.extractVersion()

}