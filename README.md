# Power Schedule Widget

Android home-screen widget (4x2) for Energy-UA power outage schedules.

## Queue selection
Open the app and choose any queue/subgroup from 1.1 through 6.2, then tap **Save and refresh**. The app uses Energy-UA queue pages (`https://energy-ua.info/cherga/{queue}-{subgroup}`), so no street address is stored or required.

## Build APK
Push this project to GitHub and run **Actions → Build Android APK**. Download the `PowerScheduleWidget-debug` artifact from the completed workflow.

The widget shows the published schedule, not a live voltage measurement. Emergency outages may differ from the planned schedule.
