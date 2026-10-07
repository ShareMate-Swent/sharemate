// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

/** Image distante et crédits, indépendants du fournisseur et des ressources visuelles Android. */
data class FoodImage(
    val url: String,
    /** Null si l'API ne fournit pas de nom d'auteur. */
    val author: String?,
    val sourceName: String?,
    /** Page de la photo à utiliser comme lien d'attribution. */
    val sourceUrl: String,
    /** Titre original, sans substitution par le titre recherché s'il est absent. */
    val title: String?,
    /** Identifiant Creative Commons : « cc0 » ou « by ». */
    val license: String,
    val licenseUrl: String,
    val licenseVersion: String?,
    val authorUrl: String?
)
