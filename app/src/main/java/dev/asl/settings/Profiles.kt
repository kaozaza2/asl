package dev.asl.settings

import dev.asl.core.AslSettings
import dev.asl.core.Distro
import dev.asl.core.Profile
import java.util.UUID

const val DISTRO_PROFILE_SOURCE = "dev.asl.distro"

/** The profile WT's WSL generator would create for [distro]; its guid is stable across launches. */
fun distroProfile(distro: Distro) = Profile(
    guid = "{${UUID.nameUUIDFromBytes("$DISTRO_PROFILE_SOURCE/${distro.id}".toByteArray())}}",
    name = distro.name,
    source = DISTRO_PROFILE_SOURCE,
    distroId = distro.id,
)

fun AslSettings.appearanceOf(profile: Profile) = profile.appearance ?: profiles.defaults

fun AslSettings.scheme(name: String) = schemes.firstOrNull { it.name == name } ?: BuiltInSchemes.first()

fun AslSettings.visibleProfiles(distros: List<Distro>) =
    profiles.list.filter { p -> !p.hidden && (p.distroId == null || distros.any { it.id == p.distroId }) }

fun AslSettings.launchProfile(distros: List<Distro>) =
    visibleProfiles(distros).let { visible -> visible.firstOrNull { it.guid == defaultProfile } ?: visible.firstOrNull() }
