"""Run after :app:compileDebugKotlin. Verify real Room SQL using in-memory SQLite."""
import json
import re
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "app/src/main/java/com/lvjian/app/data"
generated = (ROOT / "app/build/generated/ksp/debug/java/com/lvjian/app/data/AppDatabase_Impl.java").read_text(encoding="utf-8")
schema = re.search(r'db.execSQL\(("CREATE TABLE IF NOT EXISTS `messages` .*?")\);', generated).group(1)
schema = json.loads(schema)
queries = {}
dao = (DATA / "Daos.kt").read_text(encoding="utf-8")
for match in re.finditer(r'@Query\(\s*("""[\s\S]*?"""|"[^"\n]*")\s*\)\s*(?:suspend\s+)?fun\s+(\w+)', dao):
    raw, method = match.groups()
    queries[method] = raw[3:-3] if raw.startswith('"""') else json.loads(raw)
db = sqlite3.connect(":memory:")
db.row_factory = sqlite3.Row
# v7 schema differs by exactly the new column. Existing row must survive ALTER.
db.execute(schema.replace(", `mergedIntoId` INTEGER NOT NULL", ""))


def insert(row_id, **changes):
    values = {col[1]: ("" if col[2] == "TEXT" else 0) for col in db.execute("PRAGMA table_info(messages)")}
    values.update(id=row_id, appPackage="com.tencent.mm", groupName="课程群", sender="老师", text=f"原文{row_id}", timestamp=row_id, **changes)
    names = ",".join(values)
    db.execute(f"INSERT INTO messages ({names}) VALUES ({','.join('?' for _ in values)})", list(values.values()))


def run(method, **values):
    return db.execute(queries[method], values).fetchall()


insert(1)
migration = re.search(r'db.execSQL\("(ALTER TABLE messages ADD COLUMN mergedIntoId[^"\n]*)"\)', (DATA / "AppDatabase.kt").read_text(encoding="utf-8")).group(1)
db.execute(migration)
assert db.execute("SELECT text, mergedIntoId FROM messages WHERE id=1").fetchone()[:] == ("原文1", 0)
insert(2, aiState=4)
insert(3, aiState=4)
# Same SQL and binding expansion Room uses for a list parameter.
db.execute(queries["markMerged"].replace(":ids", ":first,:second"), {"target": 1, "first": 2, "second": 3})
assert [r["id"] for r in run("pendingFlow")] == [1]
assert not run("pendingBatchAll")
assert [r["text"] for r in run("mergedSources", id=1)] == ["原文2", "原文3"]
assert run("countDuplicate", app="com.tencent.mm", group="课程群", sender="老师", text="原文2", ts=2)[0][0] == 1

run("setStatus", id=1, status=1)
assert [r["id"] for r in run("doneFlow")] == [1]
undo = [dict(r) for r in run("allDone")]
assert {r["id"] for r in undo} == {1, 2, 3}
run("clearDone")
assert db.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == 0
for row in undo:
    db.execute(f"INSERT INTO messages ({','.join(row)}) VALUES ({','.join('?' for _ in row)})", list(row.values()))
assert len(run("mergedSources", id=1)) == 2
run("setStatus", id=1, status=0)
run("setFilterResult", id=1, filtered=1, rule="测试", aiState=2)
assert run("filteredCountFlow")[0][0] == 1
assert len(run("allFiltered")) == 3
run("clearFiltered")
assert db.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == 0
print("PASS: v7→v8 preserves data; merged sources stay out of lists; raw notification dedup, clear and undo preserve all sources.")
