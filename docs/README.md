# VAP technical documentation

See the [main README](../README.md) for the app overview, installation, and current build commands. Public release history is in the [changelog](../CHANGELOG.md).

The documents below record design decisions and reports from individual development stages. Their dates and results refer to the code and device specified in each report. Early migration and test statuses are historical records, not a summary of the current release. Historical development reports are currently written in Russian.

| Area | Documents |
| --- | --- |
| Planning and code provenance | [Plan](plan.md), [audit](audit.md), [migration map](migration-map.md), [feature matrix](feature-matrix.md) |
| Interface | [Design specification](design-spec.md), [UI kit](ui-kit.md), [branding](brand/logo-concepts/README.md) |
| Library | [Video and folder access](library-access.md) |
| Player | [Playback](stage5-playback.md), [controls](stage5-controls.md), [display](stage5-display.md), [image processing](stage5-image.md) |
| Audio and subtitles | [Audio](stage6-audio.md), [subtitles](stage6-subtitles.md), [ASS](stage6-ass.md), [online search](stage6-online.md) |
| Native build | [libass / FreeType / HarfBuzz / FriBidi](subtitle-native-build.md) |
| Network and casting | [Network sources](stage7-network.md), [servers](stage7-servers.md), [DLNA and Cast](stage7-cast.md) |
| AI tools | [Whisper](stage8-whisper.md), [translation](stage8-translation.md), [Smart Sync](stage8-sync.md) |
| Performance | [Measurement plan](perf-plan.md), [tools](../tools/perf/README.md) |
