/*
 * FediFerry — share a meme screenshot straight to Mastodon.
 * Copyright (C) 2026 Jasper Ramthun
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.fediferry.server

import app.fediferry.api.AiModelData
import app.fediferry.api.CleanupArea
import app.fediferry.api.CropRect
import app.fediferry.api.DeriveRequest
import app.fediferry.api.ProfileRuleData
import app.fediferry.api.SettingsKinds
import app.fediferry.media.ScreenshotCropper
import app.fediferry.media.cleanup.CleanupRule
import app.fediferry.media.cleanup.EditWireFormat
import app.fediferry.media.cleanup.EraseMask
import app.fediferry.media.cleanup.EraseRequest
import app.fediferry.media.cleanup.HttpImageEditProvider
import app.fediferry.media.cleanup.ImageCleaner
import app.fediferry.media.cleanup.ImageEditProvider
import app.fediferry.media.cleanup.MaskPolarity
import app.fediferry.media.cleanup.Region
import app.fediferry.media.cleanup.TreatmentKind
import app.fediferry.server.db.Media_asset
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * A shared screenshot readied as the phone readies its own: trimmed when
 * [trim] (the phone's setting), then cleaned with the project's default
 * profile. The result names [picture] as its original, so the edit can be
 * undone; with nothing to do, [picture] itself comes back.
 */
internal suspend fun Services.readyScreenshot(projectId: String, picture: Media_asset, trim: Boolean): Media_asset {
    val bytes = media.file(projectId, picture.sha256).readBytes()
    val crop = if (trim) screenshotTrim(bytes, picture.mime) else null
    val areas = defaultProfileAreas(projectId)
    if (crop == null && areas.isEmpty()) return picture
    val model = if (areas.any { it.treatment == TreatmentKind.AI_ERASE.name }) imageModel(this, projectId, null, outboundHttp) else null
    val request = DeriveRequest(crop = crop, areas = areas.takeIf { it.isNotEmpty() })
    val derived = derive(bytes, picture.mime, request, areas, model) ?: return picture
    return media.store(projectId, derived.bytes, derived.mime, parentId = picture.id, recipe = settingsJson.encodeToString(DeriveRequest.serializer(), request))
}

/** The project's default clean-up profile's areas, in order; none without a default. */
private fun Services.defaultProfileAreas(projectId: String): List<CleanupArea> {
    val profile = settings.list(projectId, SettingsKinds.CLEANUP_PROFILE)
        .mapNotNull { runCatching { settingsJson.decodeFromJsonElement(ProfileRow.serializer(), it.data) }.getOrNull() }
        .firstOrNull { it.isDefault } ?: return emptyList()
    return settings.list(projectId, SettingsKinds.CLEANUP_RULE)
        .mapNotNull { runCatching { settingsJson.decodeFromJsonElement(ProfileRuleData.serializer(), it.data) }.getOrNull() }
        .filter { it.profileId == profile.id && it.enabled }
        .sortedBy { it.sortOrder }
        .map { CleanupArea(it.left, it.top, it.right, it.bottom, it.treatment) }
}

/** What the server needs of a clean-up profile the phone synced. */
@kotlinx.serialization.Serializable
private data class ProfileRow(val id: String, val isDefault: Boolean = false)

/** A picture made from another: its bytes and type, and why the model was not used, if it failed. */
internal class Derived(val bytes: ByteArray, val mime: String, val aiFailure: String? = null)

/** The image model to erase with, as the project has it configured. */
internal class ImageModel(val provider: ImageEditProvider, val polarity: MaskPolarity)

private val settingsJson = Json { ignoreUnknownKeys = true }

/** The project's AI models of [kind]: the one asked for, else the default, else the first. */
internal fun pickModel(settings: SettingsStore, projectId: String, kind: String, id: String?): AiModelData? =
    settings.list(projectId, SettingsKinds.AI_MODEL)
        .mapNotNull { runCatching { settingsJson.decodeFromJsonElement(AiModelData.serializer(), it.data) }.getOrNull() }
        .filter { it.kind == kind }
        .sortedBy { it.sortOrder }
        .let { list -> list.firstOrNull { it.id == id } ?: list.firstOrNull { it.isDefault } ?: list.firstOrNull() }

/** The project's image model, ready to erase; null when none is configured. */
internal fun imageModel(services: Services, projectId: String, id: String?, http: OkHttpClient): ImageModel? {
    val model = pickModel(services.settings, projectId, "IMAGE_EDIT", id) ?: return null
    val key = services.secrets.get(projectId, "ai-model:${model.id}").orEmpty()
    val format = runCatching { EditWireFormat.valueOf(model.wireFormat) }.getOrDefault(EditWireFormat.MULTIPART)
    val polarity = runCatching { MaskPolarity.valueOf(model.maskPolarity) }.getOrDefault(MaskPolarity.TRANSPARENT_HOLE)
    return ImageModel(HttpImageEditProvider(http, model.endpoint, model.model, key, format), polarity)
}

/**
 * Applies [request] to a picture: the crop first, then the clean-up [areas]
 * on what is left. Areas marked for the model go to [model] with the
 * request's instruction; when it fails or there is none, they are filled from
 * their surroundings instead, as on the phone, and the reason comes back.
 */
internal suspend fun derive(
    bytes: ByteArray,
    mime: String,
    request: DeriveRequest,
    areas: List<CleanupArea>,
    model: ImageModel? = null,
): Derived? {
    if (!mime.startsWith("image/") || mime == "image/gif") return null
    var image = ImageIO.read(bytes.inputStream()) ?: return null
    request.crop?.let { c -> image = crop(image, c) ?: return null }
    var aiFailure: String? = null
    if (areas.isNotEmpty()) {
        var rules = areas.map { a ->
            CleanupRule(Region(a.left, a.top, a.right, a.bottom), runCatching { TreatmentKind.valueOf(a.treatment) }.getOrDefault(TreatmentKind.FILL))
        }
        val forModel = rules.filter { it.treatment == TreatmentKind.AI_ERASE }
        if (forModel.isNotEmpty()) {
            erase(image, forModel, model, request.instruction).fold(
                onSuccess = { edited ->
                    image = edited
                    // The model has done those areas; they are not filled as well.
                    rules = rules - forModel.toSet()
                },
                onFailure = { aiFailure = it.message ?: it.javaClass.simpleName },
            )
        }
        if (rules.isNotEmpty()) {
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            // ImageCleaner fills whatever is still marked for the model.
            val cleaned = ImageCleaner.apply(pixels, image.width, image.height, rules)
            image = BufferedImage(cleaned.width, cleaned.height, BufferedImage.TYPE_INT_ARGB).apply {
                setRGB(0, 0, cleaned.width, cleaned.height, cleaned.pixels, 0, cleaned.width)
            }
        }
    }
    val (out, type) = encode(image, jpeg = mime == "image/jpeg")
    return Derived(out, type, aiFailure)
}

private suspend fun erase(image: BufferedImage, rules: List<CleanupRule>, model: ImageModel?, instruction: String?): Result<BufferedImage> {
    model ?: return Result.failure(IllegalStateException("no image model configured"))
    val maskPixels = EraseMask.build(image.width, image.height, rules.map { it.region }, model.polarity)
        ?: return Result.failure(IllegalStateException("nothing marked to erase"))
    val mask = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).apply {
        setRGB(0, 0, image.width, image.height, maskPixels, 0, image.width)
    }
    val request = EraseRequest(
        image = png(image),
        mimeType = "image/png",
        mask = png(mask),
        instruction = instruction?.takeIf { it.isNotBlank() } ?: DEFAULT_INSTRUCTION,
    )
    return model.provider.erase(request).mapCatching { bytes ->
        val edited = ImageIO.read(bytes.inputStream()) ?: error("the model returned something that is not an image")
        // A different size would put every other area in the wrong place.
        check(edited.width == image.width && edited.height == image.height) {
            "the model returned a ${edited.width}x${edited.height} image, expected ${image.width}x${image.height}"
        }
        edited
    }
}

private fun crop(image: BufferedImage, c: CropRect): BufferedImage? {
    val x = (c.left.coerceIn(0f, 1f) * image.width).toInt()
    val y = (c.top.coerceIn(0f, 1f) * image.height).toInt()
    val w = ((c.right.coerceIn(0f, 1f) - c.left.coerceIn(0f, 1f)) * image.width).toInt().coerceAtMost(image.width - x)
    val h = ((c.bottom.coerceIn(0f, 1f) - c.top.coerceIn(0f, 1f)) * image.height).toInt().coerceAtMost(image.height - y)
    if (w < 8 || h < 8) return null
    return image.getSubimage(x, y, w, h)
}

/**
 * Where to trim a screenshot: the app's chrome around the picture, found as
 * the phone finds it, on a copy no longer than [ANALYSIS_MAX_EDGE]. Null when
 * nothing is to be trimmed or the finding is not sure enough to act on alone.
 */
internal fun screenshotTrim(bytes: ByteArray, mime: String): CropRect? {
    if (!mime.startsWith("image/") || mime == "image/gif") return null
    val image = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return null
    val scale = minOf(1f, ANALYSIS_MAX_EDGE.toFloat() / maxOf(image.width, image.height))
    val w = (image.width * scale).toInt().coerceAtLeast(1)
    val h = (image.height * scale).toInt().coerceAtLeast(1)
    val small = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply {
        createGraphics().apply { drawImage(image.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null); dispose() }
    }
    val pixels = small.getRGB(0, 0, w, h, null, 0, w)
    val found = ScreenshotCropper.detect(pixels, w, h) ?: return null
    if (found.confidence < AUTO_TRIM_MIN_CONFIDENCE) return null
    val c = found.crop
    if (c.width >= w - 2 && c.height >= h - 2) return null
    return CropRect(c.left.toFloat() / w, c.top.toFloat() / h, c.right.toFloat() / w, c.bottom.toFloat() / h)
}

private fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

/** JPEG stays JPEG (at high quality); everything else becomes PNG, which loses nothing. */
private fun encode(image: BufferedImage, jpeg: Boolean): Pair<ByteArray, String> {
    val out = ByteArrayOutputStream()
    if (jpeg) {
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.92f
        }
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(rgb, null, null), params)
        }
        writer.dispose()
        return out.toByteArray() to "image/jpeg"
    }
    ImageIO.write(image, "png", out)
    return out.toByteArray() to "image/png"
}

/** The phone's own default; kept in step with `CleanupPipeline.DEFAULT_INSTRUCTION`. */
private const val DEFAULT_INSTRUCTION =
    "Remove the marked overlay and continue the surrounding image naturally. Do not add anything new."

/** The phone analyses screenshots at this size too. */
private const val ANALYSIS_MAX_EDGE = 720

/** As sure as the phone has to be before it trims without asking. */
internal const val AUTO_TRIM_MIN_CONFIDENCE = 0.72f
