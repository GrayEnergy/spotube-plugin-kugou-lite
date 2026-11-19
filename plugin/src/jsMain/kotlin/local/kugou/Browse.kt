package local.kugou

import org.spotube.plugin_interfaces.plugin_apis.metadata.browse.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.*

internal class Browse(private val playlists: Playlists) : MetadataBrowseAPI {
    override suspend fun featured() = emptyList<MetadataBrowseItem>()
    override suspend fun genres() = listOf(MetadataBrowseGenre("library", "音乐库"))

    override suspend fun list(genreId: String, pagination: PaginationStrategy?): PaginationResult<MetadataBrowseSection> {
        if (genreId != "library") return PaginationResult(emptyList(), 0, null)
        val page = sublist(genreId, "playlists", pagination)
        val sections = if (page.items.isEmpty()) emptyList() else listOf(
            MetadataBrowseSection("我的歌单", items = page.items, moreLink = "playlists")
        )
        return PaginationResult(sections, sections.size, null)
    }

    override suspend fun sublist(genreId: String, sectionId: String, pagination: PaginationStrategy?): PaginationResult<MetadataBrowseItem> {
        if (genreId != "library" || sectionId != "playlists") return PaginationResult(emptyList(), 0, null)
        val page = playlists.savedPlaylists(pagination)
        return PaginationResult(page.items.map { MetadataBrowseItem.Playlist(it) }, page.totalCount, page.nextPagination)
    }
}
