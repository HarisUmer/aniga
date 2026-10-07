package com.aniga.backend.source;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fills {@code source_catalog} from the public Mihon and Aidoku indexes. Rows are names, languages,
 * and home URLs. Chapter images are not downloaded from those sites.
 */
@Component
public class SourceIndexRefresh {

    private static final Logger log = LoggerFactory.getLogger(SourceIndexRefresh.class);

    /** Verified 2026-08-18. index.min.json on Keiyoushi is a stub; the full file is index.json. */
    private static final List<Repo> REPOS = List.of(
            new Repo("keiyoushi", "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json"),
            new Repo("nyora-mihon", "https://raw.githubusercontent.com/Nyora-Manga/nyora-mihon/main/index.min.json"),
            new Repo("aidoku-community", "https://aidoku-community.github.io/sources/index.min.json"),
            new Repo("smexhy-yomu", "https://smexhy.github.io/yomu-aidoku-sources/index.min.json"),
            new Repo("kunihir0", "https://kunihir0.github.io/aidoku-sources/index.min.json"),
            new Repo("0blueyan0-zh", "https://raw.githubusercontent.com/0BlueYan0/aidoku-new-zh-sources/gh-pages/index.min.json"),
            new Repo("tachibana-shin", "https://raw.githubusercontent.com/tachibana-shin/aidoku-community-sources/gh-pages/index.min.json"),
            new Repo("skittyblock", "https://raw.githubusercontent.com/Skittyblock/aidoku-community-sources/gh-pages/index.min.json"),
            new Repo("moomooo95-fr", "https://raw.githubusercontent.com/Moomooo95/aidoku-french-sources/gh-pages/index.min.json"),
            new Repo("solsticeleaf-ru", "https://raw.githubusercontent.com/SolsticeLeaf/aidoku-ru-sources/gh-pages/index.min.json"),
            new Repo("alanlang", "https://raw.githubusercontent.com/AlanLang/aidoku-sources/gh-pages/index.min.json"),
            new Repo("beer-psi", "https://raw.githubusercontent.com/beer-psi/aidoku-ln-sources/gh-pages/index.min.json"),
            new Repo("dodieboy", "https://raw.githubusercontent.com/dodieboy/aidoku-dodieboy-sources/gh-pages/index.min.json"));

    private final JdbcClient jdbc;
    private final boolean enabled;
    private final RestClient http = RestClient.builder()
            .defaultHeader("User-Agent", "Aniga/0.1 (source catalog)")
            .build();

    public SourceIndexRefresh(JdbcClient jdbc,
                              @Value("${aniga.sources.refresh-indexes:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            return;
        }
        Thread.ofVirtual().name("source-index").start(this::refresh);
    }

    void refresh() {
        for (Repo repo : REPOS) {
            try {
                if (skip(repo)) {
                    continue;
                }
                String body = http.get().uri(repo.url()).retrieve().body(String.class);
                List<IndexParser.Entry> rows = IndexParser.parse(repo.id(), body);
                int wrote = 0;
                for (IndexParser.Entry row : rows) {
                    wrote += upsert(row);
                }
                log.info("Source index {}: {} rows", repo.id(), wrote);
            } catch (RuntimeException e) {
                log.warn("Source index {} skipped: {}", repo.id(), e.getMessage());
            }
        }
    }

    /** Keiyoushi is already seeded without NSFW rows, so it is fetched once to fill those in. */
    private boolean skip(Repo repo) {
        if ("keiyoushi".equals(repo.id())) {
            Long nsfw = jdbc.sql("SELECT count(*) FROM source_catalog WHERE index_repo = 'keiyoushi' AND nsfw")
                    .query(Long.class).single();
            return nsfw != null && nsfw > 0;
        }
        Long n = jdbc.sql("SELECT count(*) FROM source_catalog WHERE index_repo = :r")
                .param("r", repo.id()).query(Long.class).single();
        return n != null && n > 0;
    }

    private int upsert(IndexParser.Entry row) {
        return jdbc.sql("""
                        INSERT INTO source_catalog
                            (id, name, lang, home_url, content_hint, nsfw, extension_name, package_name, version, index_repo)
                        VALUES (:id, :name, :lang, :home, :hint, :nsfw, :ext, :pkg, :ver, :repo)
                        ON CONFLICT (id) DO UPDATE SET
                            name = EXCLUDED.name, lang = EXCLUDED.lang, home_url = EXCLUDED.home_url,
                            content_hint = EXCLUDED.content_hint, nsfw = EXCLUDED.nsfw,
                            extension_name = EXCLUDED.extension_name, package_name = EXCLUDED.package_name,
                            version = EXCLUDED.version, index_repo = EXCLUDED.index_repo""")
                .param("id", row.id())
                .param("name", row.name())
                .param("lang", row.lang())
                .param("home", row.homeUrl() == null ? "" : row.homeUrl())
                .param("hint", row.contentHint())
                .param("nsfw", row.nsfw())
                .param("ext", row.extensionName())
                .param("pkg", row.packageName() == null ? "" : row.packageName())
                .param("ver", row.version() == null ? "" : row.version())
                .param("repo", row.repo())
                .update();
    }

    private record Repo(String id, String url) {
    }
}
