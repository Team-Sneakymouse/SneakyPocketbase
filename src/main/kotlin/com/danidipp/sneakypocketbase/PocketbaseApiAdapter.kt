package com.danidipp.sneakypocketbase

import io.github.agrevster.pocketbaseKotlin.PocketbaseClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CompletableFuture

internal class PocketbaseApiAdapter(
    private val scope: () -> CoroutineScope,
    private val client: () -> PocketbaseClient,
    private val ready: (Runnable) -> Unit,
    private val subscribeAction: suspend (String) -> Unit,
    private val unsubscribeAction: suspend (String) -> Unit,
) : PocketbaseApi {
    override fun whenReady(callback: Runnable) = ready(callback)

    override fun getOne(collection: String, recordId: String): CompletableFuture<String> = async {
        client().httpClient.get(recordPath(collection, recordId)).successBody()
    }

    override fun getFullList(
        collection: String,
        batchSize: Int,
        sort: String,
        filter: String,
    ): CompletableFuture<List<String>> = async {
        val records = mutableListOf<String>()
        var page = 1
        do {
            val response = client().httpClient.get(collectionPath(collection)) {
                parameter("page", page)
                parameter("perPage", batchSize)
                if (sort.isNotBlank()) parameter("sort", sort)
                if (filter.isNotBlank()) parameter("filter", filter)
            }.successBody()
            val payload = Json.parseToJsonElement(response).jsonObject
            records += payload.getValue("items").jsonArray.map { it.toString() }
            val totalPages = payload.getValue("totalPages").jsonPrimitive.content.toInt()
            page++
        } while (page <= totalPages)
        records
    }

    override fun create(collection: String, recordJson: String): CompletableFuture<String> = async {
        client().httpClient.post(collectionPath(collection)) {
            contentType(ContentType.Application.Json)
            setBody(recordJson)
        }.successBody()
    }

    override fun update(collection: String, recordId: String, recordJson: String): CompletableFuture<String> = async {
        client().httpClient.patch(recordPath(collection, recordId)) {
            contentType(ContentType.Application.Json)
            setBody(recordJson)
        }.successBody()
    }

    override fun delete(collection: String, recordId: String): CompletableFuture<Boolean> = async {
        val response = client().httpClient.delete(recordPath(collection, recordId))
        if (!response.status.isSuccess()) {
            throw IllegalStateException("PocketBase delete failed (${response.status}): ${response.bodyAsText()}")
        }
        true
    }

    override fun subscribe(collection: String): CompletableFuture<Void> =
        asyncVoid { subscribeAction(collection) }

    override fun unsubscribe(collection: String): CompletableFuture<Void> =
        asyncVoid { unsubscribeAction(collection) }

    private fun <T> async(operation: suspend () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val job = try {
            scope().launch {
                runCatching { operation() }
                    .onSuccess(future::complete)
                    .onFailure(future::completeExceptionally)
            }
        } catch (failure: Throwable) {
            future.completeExceptionally(failure)
            return future
        }
        job.invokeOnCompletion { failure ->
            if (failure != null && !future.isDone) future.completeExceptionally(failure)
        }
        return future
    }

    private fun asyncVoid(operation: suspend () -> Unit): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        val job = try {
            scope().launch {
                runCatching { operation() }
                    .onSuccess { future.complete(null) }
                    .onFailure(future::completeExceptionally)
            }
        } catch (failure: Throwable) {
            future.completeExceptionally(failure)
            return future
        }
        job.invokeOnCompletion { failure ->
            if (failure != null && !future.isDone) future.completeExceptionally(failure)
        }
        return future
    }

    private fun collectionPath(collection: String): String =
        "/api/collections/${collection.encodeURLPathPart()}/records"

    private fun recordPath(collection: String, recordId: String): String =
        "${collectionPath(collection)}/${recordId.encodeURLPathPart()}"

    private suspend fun HttpResponse.successBody(): String {
        val body = bodyAsText()
        if (!status.isSuccess()) {
            throw IllegalStateException("PocketBase request failed ($status): $body")
        }
        return body
    }
}
