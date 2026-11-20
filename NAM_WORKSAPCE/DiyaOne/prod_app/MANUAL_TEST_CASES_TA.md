# DiyaOne MCX – முழு Manual Test Cases

இந்த பட்டியல் தற்போதைய source-ல் reachable ஆக இருக்கும் flow-களை அடிப்படையாகக் கொண்டது. குறிப்பாக active onboarding என்பது **One Time Registration** screen; பழைய Welcome / QR / SMS signup screens nav graph-ல் இருந்தாலும் active flow-ல் இல்லை. அவற்றை இந்த release-க்கு test scope-ல் சேர்க்க வேண்டாம்.

## 1. Test செய்யும் முன் தயார் செய்ய வேண்டியது

| தேவையானது | விவரம் |
|---|---|
| Device A | புதிய install அல்லது data-clear செய்யப்பட்ட Android phone; tester account A |
| Device B | account B; A-க்கு call/chat செய்யக்கூடியது |
| Device C (விருப்பம்) | conference / group chat participant |
| Network | Wi-Fi, mobile data, airplane-mode; weak/unstable network ஒன்று |
| Test data | valid 10-digit mobile numbers, invalid number, saved contact, unsaved contact, photo/video/PDF/unsupported file |
| OS cases | குறைந்தது Android 13+ ஒன்று (notification permission); Android 14+ ஒன்று (full-screen incoming call) |

ஒவ்வொரு case-லும்: **Pass/Fail, actual result, device/OS, account, screenshot/video, time, logs** பதிவு செய்யவும். Call/chat assertion க்கு இரண்டு device-களிலும் result பார்க்க வேண்டும்.

## 2. End-to-end smoke flow (ஒவ்வொரு build-க்கும் முதலில்)

| ID | Screen / flow | Steps | Expected result |
|---|---|---|---|
| SM-01 | Fresh install → registration | App install செய்து launch செய்யவும்; valid phone, display name, default/valid API + SIP domain கொடுத்து Register செய்யவும் | Crash இல்லாமல் registration முடிந்து main app திறக்க வேண்டும்; account status **Connected** ஆக வேண்டும். |
| SM-02 | Contacts → audio call | Contacts-ல் B-ஐ open செய்து Audio Call; B answer; 30 sec பேசவும்; end செய்யவும் | இரு பக்கமும் two-way audio; call duration; ended state; history entry சரியாக இருக்க வேண்டும். |
| SM-03 | Contacts → video call | B-க்கு video call செய்து answer; front/rear camera மாற்றி end செய்யவும் | இரு video preview/remote video, audio மற்றும் camera switch சரியாக இயங்க வேண்டும். |
| SM-04 | Chats → text | A-ல் B chat-க்கு `Smoke Tamil தமிழ் / English` அனுப்பவும்; B reply செய்யவும் | Message ஒருமுறை மட்டும், சரியான order/timestamp/delivery state; reopening பிறகும் history இருக்கும். |
| SM-05 | Media chat | A-ல் image அனுப்பி B download/open செய்யவும் | Thumbnail/progress/complete state; original image viewer; chat list preview சரி. |
| SM-06 | Incoming call while background | A app-ஐ recent screen/background-ல் வைத்துக் கொண்டு B-இலிருந்து call செய்யவும் | Notification/full-screen incoming UI வருகிறது; Answer/Decline வேலை செய்கிறது. |
| SM-07 | Conference | மூன்று valid accounts உள்ள conference/group-ஐ start/join செய்து அனைவரும் பேசவும் | Expected participants மட்டும் connect; audio/video UI மற்றும் leave/end behavior சரி. |
| SM-08 | Logout/re-login | Profile/Manage Profile-ல் logout/delete account என்ற active action பயன்படுத்தவும்; app relaunch; same valid account register செய்யவும் | Account-specific UI/data state பாதுகாப்பாக clear/refresh; login மீண்டும் successful; stale push/call state இல்லை. |

## 3. Installation, launch, permission, registration

| ID | Screen / flow | Steps | Expected result |
|---|---|---|---|
| ON-01 | Install | APK-ஐ supported device-ல் install செய்யவும் | Install complete; launcher name/icon சரி; first launch crash/blank screen இல்லை. |
| ON-02 | Fresh app launch | Fresh install launch செய்து back/home/relaunch செய்யவும் | One Time Registration மட்டும் காண வேண்டும்; duplicate activities அல்லது loop இல்லை. |
| ON-03 | Mandatory validation | Phone/display name/API domain/SIP domain ஒன்றை ஒன்றாக காலி வைத்து Register tap செய்யவும் | Register disabled அல்லது inline validation; invalid request போகக்கூடாது. |
| ON-04 | Mobile number format | 0, 1–9 digits, 11+ digits, alphabets, spaces, special chars, valid 10 digits முயற்சி செய்யவும் | Numeric keypad/10 digit limit; invalid format register ஆகாது; valid number மட்டும் accept. |
| ON-05 | Country code | Country-code dropdown-ல் வேறு code தேர்வு செய்து valid number register முயற்சி செய்யவும் | Selected code visibly retained; submitted identity/call address correct; layout cut ஆகாது. |
| ON-06 | Display name | Empty, Tamil/English, long name, emoji, leading/trailing spaces உடன் register செய்யவும் | Allowed content cleanly displayed; invalid/empty name case clear message; UI overflow இல்லை. |
| ON-07 | Advanced registration | Advanced settings expand/collapse; API domain, SIP domain, transport மாற்றவும் | Fields visibility/state correct; selected transport retained; collapse செய்தாலும் values தவறாமல் இருக்க வேண்டும். |
| ON-08 | Invalid server / credentials | Wrong API domain, wrong SIP domain, unreachable network மூலம் register செய்யவும் | Useful failure + Retry; spinner முடியும்; no crash; no false Connected status. |
| ON-09 | Double tap / rotation | Register button-ஐ வேகமாக பலமுறை tap; registration நடுவில் portrait↔landscape மாற்றவும் | ஒரே account/request மட்டுமே உருவாகும்; progress shown; final state consistent. |
| ON-10 | Network switch during registration | Register நடுவில் Wi-Fi off / mobile data on செய்யவும் | App hang இல்லை; clear retry/failure அல்லது successful continuation; duplicate account இல்லை. |
| ON-11 | Notifications permission | Android 13+ fresh install-ல் notification permission Allow / Deny இரண்டையும் test செய்யவும் | Allow-ல் call/message notification வரும்; Deny-ல் app functions crash ஆகாது, permission dependency தெளிவாக handle ஆகும். |
| ON-12 | Contacts permission | Contacts tab திறக்கும் போது Allow / Deny / Don't ask again test செய்யவும் | Allow-ல் device contacts import/list; deny-ல் graceful empty/explanation, repeated intrusive dialog இல்லை. |
| ON-13 | Mic/camera permission | First audio call, video call, voice-note, camera capture-ல் permission Allow/Deny செய்யவும் | Requested feature மட்டும் request செய்யும்; deny-ல் clear fallback; later Settings-ல் grant செய்ததும் feature recover ஆகும். |
| ON-14 | App update/relaunch | Registered app-ஐ force-close/relaunch மற்றும் over-install new build செய்யவும் | Account registration restored; no assistant shown unnecessarily; call/chat list not corrupted. |
| ON-15 | Process death | Chat/call/history tab-ல் இருக்கும் போது Settings மூலம் app force-stop செய்து launcher-ல் launch செய்யவும் | Stable launch; no infinite splash; account tries to reconnect and UI usable. |

## 4. Main navigation, drawer, general UI

| ID | Screen / flow | Steps | Expected result |
|---|---|---|---|
| NAV-01 | Bottom navigation | Contacts, Calls, Chats, Meetings tabs ஒவ்வொன்றையும் tap செய்யவும் | Correct list opens; selected state/highlight correct; tab content does not overlap bottom nav. |
| NAV-02 | Navigation state | ஒவ்வொரு tab-ல் scroll/search செய்து வேறு tab-க்கு சென்று திரும்பவும் | Expected list state is preserved or intentionally reset consistently; crash இல்லை. |
| NAV-03 | Back behavior | Root tab, opened contact/chat/detail/settings ஆகிய இடங்களில் Android back/gesture பயன்படுத்தவும் | Detail closes to correct parent; root does not show blank/wrong screen; no unexpected logout. |
| NAV-04 | Drawer | Top-left menu open/close; outside tap/back; every visible item tap செய்யவும் | Drawer dismisses correctly; item routes to matching screen; no overlay remains. |
| NAV-05 | Account status | Drawer/profile-ல் Connected, Connecting, Disconnected, Error நிலையை network/register switch மூலம் பார்க்கவும் | Status text/icon current state-க்கு பொருந்தும்; stale Connected இல்லை. |
| NAV-06 | Invite/share | Drawer-ல் Invite இருந்தால் tap செய்து system chooser-ல் an app தேர்வு/Cancel செய்யவும் | Invitation text includes app usage; chooser return/Cancel safe. |
| NAV-07 | Logs/API logs | Drawer-ல் Logs மற்றும் API Logs திறந்து back செல்லவும் | Correct log screen, scroll/copy/share if exposed; sensitive data UI-ல் தன்னிச்சையாக leak ஆகக்கூடாது. |
| NAV-08 | Theme/orientation | Light/Dark/system theme மற்றும் portrait/landscape-ல் root tabs + drawer பார்க்கவும் | Text/icons readable; no clipped controls; selected colors correct. |
| NAV-09 | Search keyboard | List/search box திறந்து type, clear, back/gesture செய்யவும் | Keyboard content-ஐ மறைக்காது; back முதலில் keyboard/search handle செய்யும்; no stuck focus. |

## 5. Contacts

| ID | Screen / flow | Steps | Expected result |
|---|---|---|---|
| CON-01 | Contacts list | Permission allow செய்து list load ஆக காத்திருக்கவும்; pull/return to screen refresh செய்யவும் | Native/imported + remote directory contacts expected order-ல்; duplicates/blank rows இல்லை. |
| CON-02 | Contact search | Name, partial name, phone suffix, Tamil/English, no-result search செய்யவும் | Case-insensitive relevant results; no-result state; clear restores full list. |
| CON-03 | Sorting/filter | Contacts filter/sort options எல்லாம் தேர்வு செய்யவும் | Correct sort/filter result; selected option persisted/reversible. |
| CON-04 | Add contact validation | + Add Contact → empty name/number, invalid number/SIP URI, valid name+phone முயற்சி | Invalid save blocked with message; valid contact saved exactly once. |
| CON-05 | Add contact fields | Multiple phone/SIP addresses, label/type, avatar photo ஆகியவை சேர்க்கவும் | All values visible after save/reopen; duplicate/empty row not created. |
| CON-06 | System-contact permission | New contact save நேரத்தில் Allow/Deny contacts write permission செய்யவும் | Allow-ல் expected device/app contact save; deny-ல் no crash and clear outcome. |
| CON-07 | Edit/cancel | Existing contact edit செய்து Save; மீண்டும் edit செய்து change செய்து Cancel/back செய்யவும் | Save persists; Cancel discards only unsaved changes; confirmation if shown works. |
| CON-08 | Delete contact | Contact detail → Delete → Cancel, பின்னர் Confirm செய்யவும் | Cancel retains; Confirm removes expected contact only and returns safely. |
| CON-09 | Favorite | Contact-ஐ favourite add/remove செய்யவும் | Favourite state/list updates immediately and persists after relaunch. |
| CON-10 | Contact actions | Detail screen-ல் Audio Call, Video Call, Chat, T Call என visible actions test செய்யவும் | Correct action/target opens; selection uses correct selected number if multiple numbers. |
| CON-11 | Multiple address picker | Multiple phone/SIP contact call/chat செய்யவும் | Picker shows all valid addresses; selected one alone is used; Cancel makes no call. |
| CON-12 | Long press number | Contact number/SIP-ஐ long press செய்து available Copy/Edit/Delete/default actions செய்யவும் | Action applies only to pressed address; clipboard contains accurate value. |
| CON-13 | Shared media/documents | Contact detail-ல் shared Media/Documents திறக்கவும் | Only that conversation's content; empty state correct; item opens or download starts. |
| CON-14 | Remote contact directory | Directory contact select செய்து profile/avatar/load retry test செய்யவும் | Correct contact profile/name/image; API failure shows fallback and no frozen list. |
| CON-15 | Offline contacts | Airplane mode-ல் Contacts/search/open contact செய்யவும் | Previously cached/local data usable where expected; network-only items fail gracefully. |

## 6. Calls and T Call

| ID | Screen / flow | Steps | Expected result |
|---|---|---|---|
| CAL-01 | Dial pad | Calls tab → dial pad; digits, backspace, long-press 0/+, empty call tap செய்யவும் | Digits/prefix shown correctly; delete works; empty/invalid target does not initiate malformed call. |
| CAL-02 | Outgoing audio | Saved and manual valid number-க்கு audio call செய்யவும் | Outgoing/ringing UI, remote answer, two-way audio, timer and end state correct. |
| CAL-03 | Outgoing video | Video call → answer; front/rear camera change; local preview move if supported | Two-way video/audio; no camera crash; UI reflects camera state. |
| CAL-04 | Unanswered / reject / busy | B reject, does not answer, unavailable/busy நிலைகளில் call செய்யவும் | Correct terminal screen/message and call-history type; audio focus/ringtone stopped. |
| CAL-05 | Incoming answer/decline | B→A audio/video call; A Answer மற்றும் separate run-ல் Decline செய்யவும் | Answer starts only once; decline ends remote ringing; both records correct history. |
| CAL-06 | Locked/background incoming call | A screen locked, app killed/background; B calls | Android 13+/14+ notification/full-screen handling is visible as permitted; answer/decline works without unlocking unexpectedly. |
| CAL-07 | In-call mute | Active call-ல் mute/unmute; B பேச வைத்து verify | A mute-ல் Bக்கு A audio இல்லை; unmute-ல் returns; icon state correct. |
| CAL-08 | Speaker/Bluetooth/audio route | Earpiece, speaker, wired headset, Bluetooth (available devices) மாற்றவும் | Selected route used; UI actual route-ஐ காட்டும்; disconnect fallback valid. |
| CAL-09 | Hold/resume | A hold/resume; B holds A; controls while held test செய்யவும் | Remote gets hold state; resume restores media; unavailable controls disabled while needed. |
| CAL-10 | In-call dial pad/DTMF | Active call → keypad → 0-9, *, # அனுப்பி close செய்யவும் | Tones sent once; call audio/control state unaffected; keyboard/bottom sheet closes. |
| CAL-11 | New call | A↔B active call போது New Call மூலம் C-க்கு call; B/C transition inspect செய்யவும் | Existing call handled (hold) correctly; separate calls list accurate; no unintended merge/end. |
| CAL-12 | Transfer | Active call → Transfer → valid B/C target; Cancel and Confirm runs | Cancel preserves call; transfer route/termination correct; history reflects actual result. |
| CAL-13 | Merge conference | இரண்டு active calls உருவாக்கி Merge/Create conference செய்யவும் | Confirm dialog; all expected participants in conference; failure message if server rejects. |
| CAL-14 | Recording | Recording enabled build/device-ல் active call record start/stop; list/playback check | Consent/indicator if expected; no duplicate file; recording playable and duration reasonable. |
| CAL-15 | Call interruption | Active call-ல் phone lock, rotate, home/reopen, Wi-Fi↔mobile switch செய்யவும் | Media recovers or clear disconnect reason; no ghost call/duplicate notification. |
| CAL-16 | Call statistics/security | Active call-ல் Stats/media encryption/SAS controls available என்றால் open/close/validate செய்யவும் | Accurate non-empty info; security warning/confirm action does not crash/cut call unexpectedly. |
| CAL-17 | T Call language setup | Settings → Calls → **Call language**-ல் each language select; reopen app/settings செய்யவும் | Selection saved and displayed accurately. |
| CAL-18 | T Call start | Contact detail → **T Call** → choose Audio and Video in separate runs | Correct target/call mode starts; language selection dialog value used. |
| CAL-19 | T Call language change | T Call active போது Language action மூலம் change/cancel செய்யவும் | Cancel keeps old; confirm updates language and call remains stable. |
| CAL-20 | Call history filters | Calls/History-ல் All, Regular, T Calls filters தேர்வு செய்யவும் | Each filter includes only matching call type; counts/date order correct. |

## 7. Chats, files and translation

| ID | Screen / flow | Steps | Expected result |
|---|---|---|---|
| CHT-01 | Start chat | Chats → New chat; search/select saved contact and unsaved valid number | Correct one-to-one chat opens; no empty room on Cancel. |
| CHT-02 | Text messages | Empty, whitespace, English, Tamil, emoji, long multiline text அனுப்பவும் | Empty/whitespace blocked; valid text preserves characters/line breaks and appears once. |
| CHT-03 | Delivery/read state | A sends while B online/offline; B opens chat | Sending/delivered/read indicators progress correctly; offline send eventually retries or reports failure. |
| CHT-04 | Draft | Type unsent text; back/tab switch/force-close/reopen conversation | Draft is retained or cleared consistently by design; never sent without tapping Send. |
| CHT-05 | Reply | Text, image and voice message-க்கு Reply செய்து B receive செய்யவும் | Reply preview maps to correct original; tap navigates/highlights original if supported. |
| CHT-06 | Edit | Own sent text edit செய்து save/cancel; edit after reconnect | Cancel retains original; save changes only target and shows expected edited state. |
| CHT-07 | Delete | Own message delete/cancel/confirm; incoming message behavior check | Correct confirmation; only selected message affected; no list corruption. |
| CHT-08 | Forward | Text/image/file-ஐ one and multiple recipients-க்கு forward; Cancel run | Correct payload/type forwarded, marked forwarded if UI supports; no duplicate/cross-chat message. |
| CHT-09 | Reactions | Incoming/outgoing message-க்கு emoji add/change/remove செய்யவும் | Reaction count/icon correct on both devices; repeat tap behaves consistently. |
| CHT-10 | Conversation menu | Chat menu-ல் visible actions (call/video call, group call, search/info/delete/clear/leave) எல்லாம் test செய்யவும் | Each action matches selected room; destructive actions confirmation/Cancellation safe. |
| CHT-11 | Group chat | 3 participants-உடன் group create, subject set/edit, participant add/remove/leave செய்யவும் | Membership/subject messages synced; removed user cannot continue sending. |
| CHT-12 | Attachment picker | Attach → image/video/document/camera; picker Cancel; unsupported/large file test | Correct picker opens; Cancel does nothing; invalid file gives clear error. |
| CHT-13 | Camera attachment | Attach → camera; Allow/Deny camera; capture/send | Permission handles gracefully; captured item sent once with correct thumbnail. |
| CHT-14 | Media transfer | Large image/video send போது progress; receiver download; network off mid-transfer then retry | Progress/status realistic; retry works; no perpetual 0% or duplicate downloaded file. |
| CHT-15 | Document/PDF | PDF/other document send, download, tap open, back | Filename/size visible; appropriate viewer opens; return retains conversation. |
| CHT-16 | Media gallery | Conversation Info → Media; image/video list browse/open/delete if exposed | Only current room media; grid/list order correct; media viewer swipe/back works. |
| CHT-17 | Documents gallery | Conversation Info → Documents; download/open a document | Current-room documents only; empty/error/download state correct. |
| CHT-18 | Voice message | Mic Allow/Deny; press/hold record, cancel gesture, send, play/pause/seek | No recording after denial; cancel does not send; sent voice duration/playback works. |
| CHT-19 | Chat notification | B sends A text/media while A foreground/background/killed | Notification sender/preview respects configuration; tap opens exact conversation; no duplicate notification. |
| CHT-20 | Hide notification content | Settings → Chats → hide message content on; send message; off and repeat | On: sensitive text absent; off: expected preview; notification action still correct. |
| CHT-21 | Mark read on notification dismiss | Toggle setting on/off; receive then swipe-dismiss notification | On: conversation becomes read; off: unread badge remains. |
| CHT-22 | Chat translation — global | Incoming Message Translation global language: None, Tamil, Telugu, English, Hindi, Chinese, Arabic, Spanish; B sends known text | None shows original; each selected language requests/displays expected translation or graceful fallback on API error. |
| CHT-23 | Translation show/original | Translated message-ல் **Show translation** / **Show original** repeatedly tap | Exact original and translated text toggle; source message/order/delivery state unchanged. |
| CHT-24 | Per-chat translation | Conversation info-ல் per-chat setting **Use global** மற்றும் one specific language தேர்வு; B sends message | Specific override affects only that chat; reverting to global restores global behavior. |
| CHT-25 | Translation offline/failure | Translation enabled நிலையில் network off/API endpoint failure; incoming text பெறவும் | Original remains readable; no stuck spinner/crash/repeated duplicate text. |
| CHT-26 | Ephemeral lifetime | Conversation ephemeral lifetime option visible என்றால் set/change/disable செய்து messages test | Chosen duration/expiry applies predictably and does not delete unrelated chats. |
| CHT-27 | Chat list | Unread chat, draft, media, translated last message உடன் list refresh/reopen | Latest preview/time/unread badge/order correct; selecting marks only that room read. |
| CHT-28 | Share into DiyaOne | Android Gallery/Files → Share → DiyaOne with text/image/multiple images | App opens recipient/conversation flow; each selected content sent once; Cancel safe. |

## 8. Meetings and conferences

| ID | Screen / flow | Steps | Expected result |
|---|---|---|---|
| MET-01 | Meetings list | Meetings tab open; no-meeting and with-meeting states; refresh/relaunch | Correct empty/list UI and chronological grouping; no stale/duplicate cards. |
| MET-02 | Schedule meeting | Create/Schedule screen: required fields empty, valid title/date/time/timezone/participants | Validation blocks invalid; valid meeting appears once with exact values. |
| MET-03 | Date/time validation | Past date, end before start, timezone change, long title/special characters try | Clear validation; time rendering consistent across A/B timezone devices. |
| MET-04 | Add participants | Search/select/deselect existing contacts and manual invalid address | Selection count/list correct; invalid address blocked; back/cancel preserves expected pending selection. |
| MET-05 | Edit meeting | Existing meeting title/time/participants edit → save; second run cancel | Save syncs updated card/details/invites; cancel keeps old values. |
| MET-06 | Share invitation | Meeting → Share invitation; choose share target and Cancel | Link/text is complete and maps to same meeting; cancel does not modify meeting. |
| MET-07 | Join waiting room | Join meeting with camera permission Allow; preview, mic/camera toggle, join | Preview works; join uses selected device states; participant enters only once. |
| MET-08 | Waiting room denial | Camera permission Deny/Don't ask again → join/leave flow | Clear outcome/fallback; app does not crash or stay stranded in waiting room. |
| MET-09 | Meeting lifecycle | Host starts; participant joins/leaves/rejoins; host ends/cancels | Correct participant states and end behavior on all devices. |
| MET-10 | Cancel/delete meeting | Cancel/Delete → Cancel then Confirm; participant receives update | Cancel path leaves meeting unchanged; confirm updates list/invitation/chat appropriately. |
| MET-11 | Take Conference | Contacts → **Take Conference** → select audio/video conference group and start | Correct group server call; users join expected conference; API failure shown without endless progress. |
| MET-12 | Active conference controls | 3+ participant conference: mute, camera, speaker/audio route, end/leave | Target/local control effects are correct; no call dropped by unrelated control. |
| MET-13 | Conference participants | Participants list → add participant/search; remove/kick with Cancel/Confirm | Valid person added; Cancel keeps person; confirm removes only chosen participant. |
| MET-14 | Conference layout | Mosaic, Speaker, Audio only layouts; rotate device | Chosen layout displays correct participants and persists for session; graceful too-many-participants message. |
| MET-15 | Conference chat | Active conference → Chat → send/receive message → back to call | Correct conference conversation; call stays active; unread badge works. |
| MET-16 | Network interruption | One participant network off/on in active conference | Other members remain valid; disconnected participant state/rejoin works; no phantom attendee. |

## 9. History and recordings

| ID | Screen / flow | Steps | Expected result |
|---|---|---|---|
| HIS-01 | Call history | Create answered, missed, rejected, T Call, conference calls; open Calls tab | Direction/type/icon, name/number, duration/date and sorting are correct. |
| HIS-02 | History detail | Tap each history item; inspect callback/message/contact actions | Detail belongs to exact call; actions target same address; missing contact handled. |
| HIS-03 | Callback | History detail/list → Call back audio/video as supported | New call starts to correct party; old log unchanged except expected new entry. |
| HIS-04 | Delete history | Delete one → Cancel/Confirm; Delete all if visible → Cancel/Confirm | Cancellation safe; confirmed deletion affects selected scope only; no crash. |
| HIS-05 | Meeting/conference history | Conference history item → associated conversation/details | Correct conference chat/detail opens or clear unavailable state. |
| REC-01 | Recordings list | Record a call; open Drawer → Recordings | New recording shows correct peer/date/duration; refresh/relaunch retained. |
| REC-02 | Playback | Play/pause/seek/rotate/background/return on a recording | Correct audio/video playback; state controls valid; no audio leak after exit. |
| REC-03 | Recording menu | Long press/menu → share/open/delete; Cancel/Confirm delete | Shared file opens correctly; delete confirmation behavior and list refresh correct. |

## 10. Profile, settings and account state

| ID | Screen / flow | Steps | Expected result |
|---|---|---|---|
| SET-01 | Manage profile | Drawer → Manage the profile; verify display name, SIP identity/device ID, registration state | Correct account shown; copy icon copies exact non-truncated value; name edit/save persists. |
| SET-02 | Registration switch | Profile Connected toggle off/on with network available/unavailable | Off unregisters/updates status; on reconnects; UI never claims Connected before success. |
| SET-03 | Devices | Profile devices section expand/collapse/load/empty/error | Loading stops; device list/empty state accurate; no repeated duplicate rows. |
| SET-04 | Account removal/logout | Delete/Sign out action → Cancel and Confirm | Cancel retains account; confirm clears session and routes safely to registration; subsequent FCM/call doesn't resurrect old account. |
| SET-05 | Calls settings | Toggle echo cancellation, adaptive rate, FEC, vibrate, auto-record; choose ringtone; calibration if exposed | Each setting changes control/value and persists after relaunch; ringtone selection/playback/cancel correct. |
| SET-06 | Chats settings | Auto-download, make media public, notification content, mark-read-on-dismiss toggle each | Toggle state persists; effect verified by a media/notification scenario. |
| SET-07 | Contacts settings | Sort, native contact editor, hide empty contacts, presence/Firebase presence | List behavior matches selection; permission/data failure does not break contacts. |
| SET-08 | Meetings settings | Show past meetings; default layout choices | List/layout follows setting; setting persists. |
| SET-09 | UI settings | Auto-show dial pad, theme, main color | Immediate UI change where applicable; readable contrast and survives restart. |
| SET-10 | Security settings | Prevent screenshots on/off; Encrypt everything confirmation Cancel/Confirm | Screenshot blocking follows switch; encryption warning irreversible path is explicit and no accidental enable. |
| SET-11 | Network/tunnel | Network/tunnel visible fields/toggles: valid, invalid and empty values; save/relaunch | Validation/error clear; values persist only when saved; no registration corruption. |
| SET-12 | Advanced Calls | DTMF RFC2833/SIP INFO, proximity, early media, auto-answer + delay | Toggle/select values persist; DTMF/auto-answer behavior matches setting without unexpected call acceptance. |
| SET-13 | Media/security advanced | Encryption type/mandatory, E2EE conference, codecs/audio device controls | Valid selections persist; incompatible choice gives clear feedback, not crash. |
| SET-14 | Android app settings link | Settings → DiyaOne Android settings; change a permission; navigate back | Correct OS app-settings page opens; returning refreshes feature availability. |
| SET-15 | Developer settings | Developer page visibility toggle; VU meter/call stats/log cleanup actions | Hidden unless enabled; each switch/action works only as labelled, confirmation for destructive cleanup. |
| SET-16 | Account advanced settings | Registrar/outbound proxy/NAT/STUN/TURN/password visible options: valid/invalid/save/cancel | Invalid URI/credentials validated or clear error; saved state persists; no silent account replacement. |

## 11. Notifications, resilience, security and compatibility

| ID | Screen / flow | Steps | Expected result |
|---|---|---|---|
| QLT-01 | Message notification actions | Notification-ல் tap, Reply/Mark read if visible, dismiss செய்து verify | Correct conversation/action result; notification state and unread count stay consistent. |
| QLT-02 | Incoming-call notification actions | Call notification-ல் Answer/Decline and full-screen tap use செய்யவும் | One answer/decline operation only; no duplicate CallActivity or audio service. |
| QLT-03 | App restart / token refresh | Device reboot; app open; B sends/calls A | Registration and FCM message/call delivery recover within expected server policy. |
| QLT-04 | Permission revoked later | OS Settings-ல் Notifications, Mic, Camera, Contacts permissions ஒன்றாக revoke செய்து feature test | Only affected feature fails with understandable recovery route; no crash. |
| QLT-05 | Network modes | Wi-Fi, mobile data, Wi-Fi→data switch, airplane mode, captive/weak networkல் register/call/chat/file test | Correct status/retry/failure; no indefinite loader or duplicate operation. |
| QLT-06 | Data/background restrictions | Battery optimization/Data Saver/background restriction on செய்து incoming message/call test | App behaves within OS restriction; user-visible limitation graceful; no crash/ANR. |
| QLT-07 | Orientation & multi-window | Root/list/chat/call/meeting screens-ல் rotate; supported device-ல் split-screen/resizing | Current critical state survives; no stretched/cut controls or lost active call. |
| QLT-08 | Accessibility | TalkBack, large font, display scaling, keyboard navigation மூலம் registration/call/chat test | All buttons have meaningful labels, focus order works, text not clipped; critical state announced. |
| QLT-09 | Privacy | Lock screen and notification preview; screenshot-prevention setting; logs screens inspect | Sensitive message not shown when configured; screenshot blocking works; tokens/passwords never displayed in normal UI. |
| QLT-10 | Stress | 20 quick tab switches, repeated chat open/back, 10 rapid send taps, repeated incoming calls | No ANR/crash/duplicate sends/ghost calls; UI remains responsive. |
| QLT-11 | Device/OS matrix | Minimum supported Android, Android 13, Android 14+, different OEMs on smoke flow run | No OS-specific permission/call notification regression; findings capture OS/OEM. |
| QLT-12 | Language/content matrix | Tamil, English, mixed Unicode, RTL Arabic, emoji in contact/chat/meeting titles | Text renders and persists correctly; no mojibake/layout overflow; translation fallback safe. |

## 12. Defect report format

ஒரு issue-க்கு கீழ்கண்ட format-ல் பதிவு செய்தால் developer reproduce செய்ய முடியும்:

```text
ID: CHT-24
Build/version:
Device / Android / network:
Accounts used:
Precondition:
Steps:
Actual result:
Expected result:
Reproducibility: Always / Intermittent (x/y)
Evidence: screenshot/video + relevant time + exported log
Severity: Blocker / Critical / Major / Minor
```

### Suggested priority

**P0 / release blocker:** registration, incoming/outgoing call, two-way audio, message send/receive, notification answer/decline, account data loss/crash/ANR/security leak.

**P1:** video, T Call, conference, files/voice message, translation, history/recordings.

**P2:** sorting, theme, optional advanced/developer settings, visual alignment.
