# MacroDroid template

Import `artifacts/NeedleBub-OTP-Notifications.macro` into MacroDroid to create a
disabled notification-to-OTP automation.

Before enabling it:

1. Install NeedleBub and the bundled OTP Extractor capability pack.
2. Open the imported macro's **Notification Received** trigger.
3. Select only the apps whose notifications should be checked for codes.
4. Do not select MacroDroid or NeedleBub as source apps.
5. Confirm the **NeedleBub inference** action shows **Run OTP Extractor
   locally**, then enable the macro.

The template passes the notification application, title, and message through
MacroDroid Magic Text:

```text
App: {not_app_name}
Title: {not_title}
Message: {notification}
```

It waits up to ten seconds for the plug-in result and preserves these local
string variables:

- `nb_matched`
- `nb_tool`
- `nb_result_json`
- `nb_error_code`
- `nb_code`
- `nb_source`

When `nb_matched` is exactly `true`, MacroDroid copies `nb_code` to the
clipboard and displays a **NeedleBub OTP** notification. A no-match, busy
result, or runtime error does not run either follow-up action.

The plug-in chooser exposes every installed NeedleBub capability pack whose
manifest includes the `external` surface. To adapt the template to another
installed external capability, edit the **NeedleBub inference** action and
select that pack; its declared output variables will be offered by MacroDroid.

The file is a local template only. Do not upload it to MacroDroid's online
template store without reviewing its source-app selection and privacy impact.
