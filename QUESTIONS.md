# Questions before starting the pre-graduation practice report

Each question has the default that will be used if no answer is given.
Write the answer under the question, or reply "defaults".

## 1. Supervisor from the enterprise

The order (приказ 626/05) gives the place (ЦУО ИРИТ-РТФ УрФУ) and the UrFU supervisor
(Чагаева О.Л., ст. преподаватель), but not a «Руководитель практики от предприятия» for the
title page and the Отзыв. Who is it (name and position)?

- **Default:** a blank line is left for you to fill in.

**Answer:**

## 2. Checks that cannot be run in the cloud session

Some ПМИ checks need things the cloud session does not have: five physical smartphones,
TalkBack, real push/e-mail delivery, 1000-run on-device timings. How should they appear in the
protocol (Приложение Б)?

- **Default:** they are recorded as «не проводилась» with the reason and listed under
  «необходимые доработки», and you get a checklist to run them on your own phone.
- Everything else will actually be run and recorded with real numbers: server functions, the
  50/100/200-user load test, categorisation accuracy, the portfolio control calculation,
  backup/restore. No measurements will be invented.

**Answer:**

## 3. Size of Приложение А (source code)

The system in the ТЗ is an Android app (Kotlin, Jetpack Compose), a server (Python, FastAPI,
PostgreSQL) and an ML service, so a full listing would run to hundreds of pages. Full listing,
or key modules only?

- **Default:** key modules of each subsystem in the appendix, laid out as in the teacher's
  template (А.1 package, А.1.1 class), with the complete code in this repository.

**Answer:**

## 4. Organisation names

The course project uses fictional ООО «ФинТех Старт» (заказчик) and ООО «ФинСофт Лаб»
(разработчик) with invented signatories, while the practice is officially at ЦУО ИРИТ-РТФ УрФУ.
Which should the protocol and report use?

- **Default:** keep the course-project names so the ТЗ, ПМИ and protocol stay consistent, and
  state in the introduction that the practice took place at ЦУО ИРИТ-РТФ УрФУ.

**Answer:**

## 5. Test dates

The ПМИ schedules preliminary tests for 19–26.02.2027, but the practice runs 02.11–27.12.2026
and the report is due 11.01.2027. Which dates go in the protocol?

- **Default:** December 2026, inside the practice period, with a note that the schedule was
  moved.

**Answer:**

## 6. External service accounts

Is there a Firebase project (`google-services.json`) for push, or an SMTP account for e-mail,
that should be wired in?

- **Default:** no. Push and e-mail go through adapters that log locally until keys are set in
  the config.

**Answer:**

## 7. Building the Android app in the cloud session

The Android SDK download host (`dl.google.com`) is blocked in the cloud environment, so the
Kotlin code can be written there but not compiled. May access to that host be requested?

- **Default:** yes. You will get an approval prompt; if you decline, the app is written without
  compiling and you build it in Android Studio.
- With access the APK can be compiled, its JVM tests run, and real screens rendered for the user
  manual where possible; otherwise the manual uses images from the Figma prototype.

**Answer:**

## 8. Title-page codes

Planned values: ОКПД2 58.29.32 (as in the course project), personal number 008 (position in
the order) and a УДК chosen under 004. Are there different values?

- **Default:** those.

**Answer:**

## 9. Deliverables

Planned set: the report as `.docx` plus a PDF copy, the filled-in «Индивидуальное задание», a
draft «Отзыв руководителя», and the code in `server/` and `mobile/` folders of this repository.
Anything to add or drop?

- **Default:** that set.

**Answer:**

## Things that cannot be used

- **The recorded briefing:** the `bbb-claster.urfu.ru` link is unreachable from the cloud
  session. If it contained requirements beyond the documents in this repository, write them
  here.
- **The `.vpp` and `.mdj` model files:** they cannot be edited there, so the ArchiMate, SysML
  and UML diagrams will be drawn anew as images.

**Notes:**
