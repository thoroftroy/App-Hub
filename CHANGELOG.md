# Wonderdesk Apphub changelog

## v0.6.5

- At night the animated background gets a stronger wash, so text stays easy to read on dark scenes

## v0.6.4

- Each app's animated scene now has its own drifting symbols (maths signs in Abacus, notes in Echo, arrows in Wayfinder and so on), and the scene is a little clearer while text stays easy to read
- Animated backgrounds reworked after your report: they are drawn at a higher resolution and smoothly at 24 frames a second without slowing the app down, and no longer show horizontal lines

## v0.6.3

- Every app window now has the animated scene behind it, in the app's own colour (for example orange water in Abacus, green in Pulse, purple in Scribble), following your Aqua, Bliss or Super look and the real sunrise and sunset. Motion Reduced shows a still picture and Off turns it off

## v0.6.2

- App icons now come from each app's own repository, so new apps show their own icon before they are installed and changed icons appear without updating
- Fixed a gap above the keyboard in screens with a bottom tab bar: the tab bar now steps aside while you type, so you can see what you are writing

## v0.6.1

- Removed Relay from the list of suite apps

## v0.6.0

- Relay, the new all-in-one messaging inbox, is now listed for install and updates

## v0.5.23

- Checking for updates is much faster: every app is checked at the same time instead of one after another, and the pause between apps is gone

## v0.5.22

- New Wonderdesk apps now appear in the list by themselves, with their own icon, without needing a new App Hub: the list is read from the App Hub project online and saved for offline use

## v0.5.21

- Polaris, the Wonderdesk web browser, is now in the list: install it and keep it updated from here

## v0.5.20

- New setting: how often to check for updates (hour, 3 hours, 6 hours or day), hourly by default

## v0.5.19

- New setting: update on mobile data too, instead of waiting for Wi-Fi

## v0.5.18

- Text size and accent colour chosen in Control Room now apply here
- New Rotate with the phone switch in Settings

## v0.5.17

- Apps updated in the background now leave a notification listing each app, the version it came from and what changed
- The first time you open App Hub after background updates it shows which apps were updated, from which version to which, and the notes for each

## v0.5.16

- Loading screens now say what they are doing instead of showing a bare spinner
- Wording tidied so no sentence needs a dash

## v0.5.15

- Updates are fully built in again. Hourly GitHub checks with no outside services. The settings switch turns background checks off entirely

## v0.5.14

- Releases now push the phone at once when instant alerts are set up. Until then the hourly check still finds them
- Background checks are hourly again. The switch in settings turns them off entirely
- New Instant release alerts row in settings shows the state and the setup steps

## v0.5.13

- Checks run by themselves every 15 minutes. A tiny ping published with each release wakes the next check early so new versions surface without opening the app
- The update check survives a restart and an App Hub update. A new background row explains the battery setting that keeps checks running
- Opening App Hub still checks when the last check is a while ago

## v0.5.12

- Opening App Hub now checks for updates when the last check is a while ago, so new releases show up without tapping refresh

## v0.5.11

- Updates are found sooner: Apphub checks every hour, and tells you once as soon as a new version is out
- The update notification has an Update now button: tap it and every app updates in the background with progress shown, no need to open Apphub (on any network, even mobile data, since you asked)

## v0.5.10

- Ripple joins the suite: install your computer's terminal on your phone from here, and get its updates like every other app

## v0.5.9

- Widgets show a realistic preview in the widget list, so you can see what you're adding
- Fixed a crash when moving around with arrow keys or Tab while a screen was closing

## v0.5.8

- Settings › Updates has a shortcut to switch off Google Play Protect's “Scan app” prompts, so Update all really is one tap
- Lighter on the battery: in the Super look the drifting sparkles settle after a few seconds without a touch (the next tap wakes them), so a screen you're reading no longer redraws 25 times a second

## v0.5.7

- Changing the look in Control Room now restyles this app's widgets straight away, with no refresh or restart

## v0.5.6

- Widgets: choose the title colour in each widget's settings (automatic, white, dark, Aqua blue, Super pink or gold), on top of the background looks

## v0.5.5

- Widgets can now match your look: new Bliss blue and Super neon backgrounds, and “Match my Wonderdesk look” follows whatever Control Room is set to

## v0.5.4

- Fixes from a full round of stress testing on every screen

## v0.5.3

- Tap the tab you're already on to glide back to the top of its list
- New Updates widget: how many Wonderdesk apps have updates, which ones, and a Check button
- Nothing required stays hidden: if Android is blocking something the app needs, a slim banner says what and takes you straight to the switch, and Settings › Permissions and access lists everything with its state
- Updates install by themselves now, like Obtainium: Apphub uses Android's own installer session, so on Android 12 and newer an update to an app Apphub installed needs no taps (Android may ask once the first time)
- New setting: Install updates automatically. Apphub checks every 6 hours and, on Wi-Fi, downloads and installs updates in the background, then tells you what it updated

## v0.5.2

- Smoother everywhere: new screens, tabs and Settings ease in instead of snapping, and buttons, tabs, chips and switches give a light tap you can feel (following your phone's touch-feedback setting)
- Android's predictive back: the back gesture shows a live preview of where you're going
- Super look: tap sparkles now match Bliss Player: soft glowing sparks with trails, drifting rainbow colours and a faint aurora, instead of big solid circles

## v0.5.1

- Much faster and smoother: the app is now built optimised (the way Play Store apps are), so screens open quicker, scrolling is fluid and the APK is about a quarter of the size
- Screens and lists no longer decode pictures on the main thread, and skip redrawing parts that didn't change

## v0.5.0

- Packrat and Memories show their new icons
- Works properly in landscape: tabs move to a rail at the side so the content keeps its height, and screens that ran out of room now scroll
- If the app ever crashes it says so next time it opens and offers to send a report (only the error, app version and phone model, with file names and links removed). Turn on automatic sending in Control Room › Wonderdesk
- Lists can no longer crash when two items share a name

## v0.4.0

- Longer app descriptions are no longer cut off
- App icons are shown larger and sharper
- If the app ever crashes or freezes, a report goes to Control Room so it can be shared and fixed
- App icon art is larger and fills its tile without being cut off

## v0.3.1

- Release notes: every update shows what's new, and each app's page lists its version history
- Refreshed descriptions for every Wonderdesk app
- App icon redrawn so nothing is cut off: round icons fill the space, the rest sit on an Aqua tile
- Smoother and lighter on the battery: the window background no longer animates, tap effects sleep until you tap, and nothing ticks while the app is in the background
- Opening the app no longer waits on Control Room to read the suite look

## v0.3.0

- Renamed Wonderdesk Apphub; shows only Wonderdesk apps
- Fixed downloads failing with 404 and 403 errors: real file names are read from each release, and update checks are paced so GitHub never blocks them
- Downloads are checked to be the right app before installing
- Up-to-date apps show a green check
