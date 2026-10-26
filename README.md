# DiyaOneMCX - API Calls Documentation & Examples

This document details all backend API calls made within the **DiyaOneMCX** application, including endpoints, request/response examples using test credentials (`phone: 7845470737`).

---

## Summary of APIs

1. [Get Mobion Contacts API](#1-get-mobion-contacts-api)
2. [Get Conferences View API](#2-get-conferences-view-api)
3. [Take Conference API](#3-take-conference-api)
4. [Chat Translation API](#4-chat-translation-api)
5. [Call-Ready Notification Acknowledgment API](#5-call-ready-notification-acknowledgment-api)
6. [Save FCM Push Token API](#6-save-fcm-push-token-api)

---

### 1. Get Mobion Contacts API
- **Endpoint:** `https://mobionglobal.com/fs_webservice/WebService.asmx/Get_MobionNumber`
- **Method:** `GET`
- **Purpose:** Fetches global contacts list.

#### Example Request (cURL):
```bash
curl -X GET "https://mobionglobal.com/fs_webservice/WebService.asmx/Get_MobionNumber" \
  -H "Accept: text/xml"
```

#### Example Response (XML):
```xml
<?xml version="1.0" encoding="utf-8"?>
<DataSet xmlns="http://tempuri.org/">
  <Table>
    <Name>Test User</Name>
    <Mobile_No>7845470737</Mobile_No>
  </Table>
</DataSet>
```

---

### 2. Get Conferences View API
- **Endpoint:** `https://mobionglobal.com/fs_webservice/WebService.asmx/GroupSetting_View`
- **Method:** `GET` (Query parameter: `Number=7845470737`)
- **Purpose:** Retrieves conference groups associated with the registered phone number.

#### Example Request (cURL):
```bash
curl -X GET "https://mobionglobal.com/fs_webservice/WebService.asmx/GroupSetting_View?Number=7845470737" \
  -H "Accept: text/xml"
```

#### Example Response (XML):
```xml
<?xml version="1.0" encoding="utf-8"?>
<DataSet xmlns="http://tempuri.org/">
  <Table>
    <Id>101</Id>
    <Groupnumber>7845470737</Groupnumber>
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
  -d "From=7845470737&To=9876543210&ConferenceId=7845470737_tcaudio"
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
  -d '{"to_number": "7845470737", "language": "ta", "message": "Hello how are you"}'
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
  -d '{"callId": "call_7845470737_xyz", "readyToken": "token_abc123"}'
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
  -d '{"phoneNumber": "7845470737", "fcmToken": "fcm_sample_token_7845470737_xyz"}'
```

#### Example Response (JSON):
```json
{
  "status": "success",
  "message": "Token saved successfully"
}
```
