rootProject.name = "backend"

// The backend app is the root project. zbiorkom-feed: the live feed client, archive and position writer shared with
// the recorder; recorder: transit-recorder, the app that polls the feed and stores positions.
include("zbiorkom-feed", "recorder")
