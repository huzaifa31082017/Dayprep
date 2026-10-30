# DayPrep with Java backend + SQLite database

```
dayprep/
  frontend/index.html     your original page, now talking to the backend
  backend/Server.java     HTTP routes (built-in Java HTTP server, no frameworks)
  backend/Db.java         all SQL / JDBC code
  backend/Util.java       password hashing, request parsing, JSON helpers
  database/schema.sql     the 4 tables (created automatically on first start)
  lib/sqlite-jdbc.jar     database driver (the only dependency)
```

## Run it
1. Install a JDK, version 17 or newer (check with `javac -version`).
2. In this folder run `./run.sh` (Mac/Linux) or `run.bat` (Windows).
3. Open http://localhost:8080

The database file `dayprep.db` is created next to `run.sh` on first start.

## API
| Method | Path | Purpose |
|---|---|---|
| POST | /api/signup | create account (email, password) |
| POST | /api/login | log in (email, password) |
| GET | /api/me | restore session on page load |
| POST | /api/logout | end session |
| PUT | /api/data | save favourites, plans, draft, profile, ratings |
| POST | /api/history | add a history entry (t) |
| DELETE | /api/history | clear history |
| POST | /api/password | change password (current, next) |
| DELETE | /api/account | delete account and all its data |

## Notes
- Passwords are salted and hashed (PBKDF2). Plain passwords are never stored.
- Traffic is plain HTTP, which is fine on localhost. Put it behind HTTPS before hosting it online.
- The "Reviews" page still stores reviews in the browser (localStorage); it was not part of login or history.
