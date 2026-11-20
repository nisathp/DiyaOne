# DiyaOneMCX - API Calls Documentation & Examples

This document details all backend API calls made within the **DiyaOneMCX** application, including endpoints, request/response examples using illustrative test data (`phone: 5550000123`).

## Android debug APK builds

Pushing Android project changes to `main` starts the [Build DiyaOne Android APK workflow](https://github.com/nisathp/DiyaOne/actions/workflows/diyaone-android-apk.yml). Open the completed run and download `diyaone-debug-apk` from the **Artifacts** section.

---
### TCAL visible

1.history_call_list_cell.xml
2.call_actions_generic.xml
3.contact_fragment.xml

## Summary of APIs
Current app-ல் HTTP API calls இவை:

| # | Method | Full path | பயன்பாடு |
|---|---|---|---|
| 1 | POST | `https://mobionglobal.info/Diyaone/api/users` | Signup |
| 2 | POST | `https://mobionglobal.info/Diyaone/api/auth/signin` | Existing/new user signin; access + refresh token பெறும் |
| 3 | POST | `https://mobionglobal.info/Diyaone/api/auth/logout` | Logout |
| 4 | GET | `https://mobionglobal.info/Diyaone/api/users` | முழு Contacts Directory (name, mobileNo, profilePictureUrl) |
| 5 | GET | `https://mobionglobal.info/Diyaone/api/users/{subscriberId}` | ஒரு contact profile URL fallback |
| 6 | GET | `https://mobionglobal.com/fs_webservice/WebService.asmx/GroupSetting_View?Number={registeredNumber}` | Conference/group details |
| 7 | SIP REGISTER | `sip:{mobileNumber}@{sipDomain}` | SIP account registration; இது HTTP API இல்லை |

API domain field-ல் user கொடுக்கும் host/IP இருந்தால் Diyaone API paths:

`https://{api-domain-or-ip}/Diyaone/api/...` (அல்லது IP என்றால் `http://{ip}/Diyaone/api/...`)

ஆக dynamic-ஆக மாறும். Contact list-க்கு `GET /Diyaone/api/users` மட்டுமே பயன்படுத்தப்படுகிறது. பழைய `Get_MobionNumber` XML WebService பயன்படுத்தப்படவில்லை.

Profile-picture upload endpoint (`/users/profile-picture`) app-ல் தற்போது call செய்யப்படவில்லை. Profile image load-க்கு #4 மட்டும் primary mapping; #5 fallback உள்ளது.


1. [Get Mobion Contacts API](#1-get-mobion-contacts-api)
2. [Get Conferences View API](#2-get-conferences-view-api)
3. [Take Conference API](#3-take-conference-api)
4. [Chat Translation API](#4-chat-translation-api)
5. [Call-Ready Notification Acknowledgment API](#5-call-ready-notification-acknowledgment-api)
6. [Save FCM Push Token API](#6-save-fcm-push-token-api)

---

### 1. Get Mobion Contacts API
- **Endpoint:** `https://mobionglobal.info/Diyaone/api/users` (or `https://{user-configured-domain}/Diyaone/api/users`)
- **Method:** `GET`
- **Header:** `Authorization: Bearer <accessToken>`, `Accept: application/json`
- **Purpose:** Fetches all registered users/contacts directory with profile pictures.

#### Example Request (cURL):
```bash
curl -X GET "https://mobionglobal.info/Diyaone/api/users" \
  -H "Accept: application/json" \
  -H "Authorization: Bearer <token>"
```

#### Example Response (JSON):
```json
[
  {
    "subscriberId": "5550000123",
    "mobileNo": "5550000123",
    "name": "Example Contact",
    "profilePictureUrl": "https://mobionglobal.info/Diyaone/api/users/profile-picture/..."
  }
]
```

---

### 2. Get Conferences View API
- **Endpoint:** `https://mobionglobal.com/fs_webservice/WebService.asmx/GroupSetting_View`
- **Method:** `GET` (Query parameter: `Number=5550000123`)
- **Purpose:** Retrieves conference groups associated with the registered phone number.

#### Example Request (cURL):
```bash
curl -X GET "https://mobionglobal.com/fs_webservice/WebService.asmx/GroupSetting_View?Number=5550000123" \
  -H "Accept: text/xml"
```

#### Example Response (XML):
```xml
<?xml version="1.0" encoding="utf-8"?>
<DataSet xmlns="http://tempuri.org/">
  <Table>
    <Id>101</Id>
    <Groupnumber>5550000123</Groupnumber>
    <Group_Name>Team Conference</Group_Name>
    <conference>audio</conference>
  </Table>
</DataSet>
```

---

### 3. Take Conference API
- **Endpoint:** `https://mobionglobal.com/fs_webservice/WebService.asmx/TakeConference`
- **Method:** `POST`
- **Content-Type:** `application/x-www-form-urlencoded; charset=UTF-8`
- **Purpose:** Schedules or initiates a conference call on the server.

#### Example Request (cURL):
```bash
curl -X POST "https://mobionglobal.com/fs_webservice/WebService.asmx/TakeConference" \
  -H "Content-Type: application/x-www-form-urlencoded; charset=UTF-8" \
  -d "From=5550000123&To=5550000124&ConferenceId=5550000123_tcaudio"
```

#### Example Response (XML / Text):
```xml
<?xml version="1.0" encoding="utf-8"?>
<string xmlns="http://tempuri.org/">Insert successful</string>
```

---

### 4. Chat Translation API
- **Endpoint:** `http://203.95.216.68:8020/translate`
- **Method:** `POST`
- **Headers:** 
  - `x-api-key: qhyvM1rr4RE2vqnvAAdfoDDC9Wlbylv-XXXX`
  - `Content-Type: application/json; charset=UTF-8`
- **Purpose:** Translates chat messages in real-time.

#### Example Request (cURL):
```bash
curl -X POST "http://203.95.216.68:8020/translate" \
  -H "x-api-key: qhyvM1rr4RE2vqnvAAdfoDDC9Wlbylv-XXX" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -d '{"to_number": "5550000123", "language": "ta", "message": "Hello how are you"}'
```

#### Example Response (JSON):
```json
{
  "status": "success",
  "translated_message": "வணக்கம் எப்படி இருக்கிறீர்கள்"
}
```

---

### 5. Call-Ready Notification Acknowledgment API
- **Endpoint:** `https://androidpushnotification.vercel.app/api/call-ready`
- **Method:** `POST`
- **Content-Type:** `application/json`
- **Purpose:** Confirms SIP registration readiness following an incoming FCM push wake notification.

#### Example Request (cURL):
```bash
curl -X POST "https://androidpushnotification.vercel.app/api/call-ready" \
  -H "Content-Type: application/json" \
  -d '{"callId": "call_5550000123_xyz", "readyToken": "token_abc123"}'
```

#### Example Response (JSON):
```json
{
  "success": true,
  "message": "Call readiness acknowledged"
}
```

---

### 6. Save FCM Push Token API
- **Endpoint:** `https://androidpushnotification.vercel.app/api/save-token`
- **Method:** `POST`
- **Headers:**
  - `x-signature: 015a3e11e51129f1e74326b993b508103506093xxxxx`
  - `Content-Type: application/json`
- **Purpose:** Registers/syncs the device's FCM token with Firestore backend for incoming VoIP calls.

#### Example Request (cURL):
```bash
curl -X POST "https://androidpushnotification.vercel.app/api/save-token" \
  -H "x-signature: 015a3e11e51129f1e74326b993b508103506093xxxxx" \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber": "5550000123", "fcmToken": "fcm_sample_token_5550000123_xyz"}'
```

#### Example Response (JSON):
```json
{
  "status": "success",
  "message": "Token saved successfully"
}
```
