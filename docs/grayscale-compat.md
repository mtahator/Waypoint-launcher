# System grayscale compatibility

Advanced mode writes `Settings.Secure.accessibility_display_daltonizer_enabled` (1/0) and
`accessibility_display_daltonizer` (0 = monochromacy) after the user runs:

```bash
adb shell pm grant app.waypoint.launcher android.permission.WRITE_SECURE_SETTINGS
```

| Device | Android | Result |
|---|---|---|
| _TODO(verify-on-device)_ | | |

Not yet verified on hardware. When the write is refused the toggle reports failure and the feature is
shown as unavailable. Accessibility services are never used for this.
