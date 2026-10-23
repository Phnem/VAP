package com.phnem.vap.ai.translation

import android.content.Context
import com.phnem.vap.ai.models.ModelFile
import com.phnem.vap.ai.models.ModelFiles
import java.io.File
import org.json.JSONArray

enum class LocalTranslationModel(val from: String, val to: String) {
    EN_RU("en", "ru"), RU_EN("ru", "en");
    val id get() = "$from-$to"
}
class TranslationModels(context: Context) {
    private val root = File(context.filesDir, "translation-models")
    private val specs: Map<LocalTranslationModel, List<ModelFile>> = JSONArray(context.assets.open("models/translation-models.json").bufferedReader().use { it.readText() }).let { rows ->
        LocalTranslationModel.entries.associateWith { model ->
            val row = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == model.id }
            val files = row.getJSONArray("files")
            List(files.length()) { index -> files.getJSONObject(index).let { ModelFile(it.getString("name"), it.getString("url"), it.getLong("bytes"), it.getString("sha256")) } }
        }
    }
    private val stores = LocalTranslationModel.entries.associateWith { ModelFiles(directory(it)) }
    fun directory(model: LocalTranslationModel) = File(root, model.id)
    fun progress(model: LocalTranslationModel) = stores.getValue(model).progress
    fun ready(model: LocalTranslationModel) = specs.getValue(model).all { stores.getValue(model).ready(it) != null }
    fun bytes(model: LocalTranslationModel) = specs.getValue(model).sumOf { it.bytes }
    fun occupied(model: LocalTranslationModel) = specs.getValue(model).sumOf { stores.getValue(model).occupied(it) }
    fun cancel() = stores.values.forEach { it.cancel() }
    fun delete(model: LocalTranslationModel) = specs.getValue(model).forEach { stores.getValue(model).delete(it) }
    suspend fun download(model: LocalTranslationModel, report: (Int) -> Unit) {
        var completed = 0L
        val total = bytes(model)
        for (spec in specs.getValue(model)) {
            stores.getValue(model).download(spec) { bytes -> report(((completed + bytes) * 100 / total).toInt()) }
            completed += spec.bytes; report((completed * 100 / total).toInt())
        }
    }
}
