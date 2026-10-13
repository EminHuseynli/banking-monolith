"""Workload and verification client for the rollback-loss experiment.

Runs inside the compose network (see docker-compose.yml, service "harness").
Every HTTP call goes through Nginx, exactly like a real client, and is logged
to <run>/http.jsonl. State between steps is kept in <run>/state.json.

Usage: rollback.py <command> <run-dir>
  wait       wait until monolith, account-service, api-gateway and nginx are healthy
  seed       (via monolith) users, accounts, deposits, a withdrawal and a transfer
  forward    after switching to blue: re-login, read accounts through account-service
  workload   service era writes: open two accounts, close one
  verify     after rollback: what does the monolith show, and what can users do
  signals    health, Prometheus targets and HTTP status metrics after the window
  summarize  build <run>/summary.json from state + files written by run.sh
  aggregate  <results-dir>: build results/summary.md from all summary.json files
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter

BASE = os.environ.get("BASE_URL", "http://nginx:80")
PROM = os.environ.get("PROM_URL", "http://prometheus:9090")
PASSWORD = "secret123"


class Run:
    def __init__(self, run_dir):
        self.dir = run_dir
        os.makedirs(run_dir, exist_ok=True)
        self.state_path = os.path.join(run_dir, "state.json")
        self.state = {}
        if os.path.exists(self.state_path):
            with open(self.state_path) as f:
                self.state = json.load(f)
        self.phase = None

    def save(self):
        with open(self.state_path, "w") as f:
            json.dump(self.state, f, indent=2)

    def http(self, method, path, body=None, token=None, base=BASE):
        url = base + path
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        started = time.time()
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                status, raw = resp.status, resp.read().decode()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read().decode(errors="replace")
        except Exception as e:  # connection refused, timeout, ...
            status, raw = 0, repr(e)
        try:
            parsed = json.loads(raw) if raw else None
        except ValueError:
            parsed = raw
        entry = {"phase": self.phase, "method": method, "path": path, "request": body,
                 "status": status, "response": parsed, "ms": int((time.time() - started) * 1000)}
        with open(os.path.join(self.dir, "http.jsonl"), "a") as f:
            f.write(json.dumps(entry) + "\n")
        return status, parsed

    # --- helpers -----------------------------------------------------------

    def login_all(self):
        for name, u in self.state["users"].items():
            status, resp = self.http("POST", "/api/auth/login", {"email": u["email"], "password": PASSWORD})
            u["token"] = resp["token"] if status == 200 and isinstance(resp, dict) else None
            u.setdefault("login_status", {})[self.phase] = status

    def view(self):
        """What each user sees under GET /api/accounts right now."""
        out = {}
        for name, u in self.state["users"].items():
            status, resp = self.http("GET", "/api/accounts", token=u["token"])
            accounts = resp if status == 200 and isinstance(resp, list) else []
            # Monolith's AccountResponse carries firstName/lastName, account-service's does not
            served_by = ("monolith" if "firstName" in accounts[0] else "account-service") if accounts else None
            out[name] = {"status": status, "served_by": served_by, "accounts": [slim(a) for a in accounts]}
        return out


def slim(a):
    return {"id": a.get("id"), "accountNumber": a.get("accountNumber"), "accountType": a.get("accountType"),
            "balance": float(a["balance"]) if a.get("balance") is not None else None,
            "status": a.get("status"), "userId": a.get("userId")}


def find(view_user, account_id):
    return next((a for a in view_user["accounts"] if a["id"] == account_id), None)


# --- commands ----------------------------------------------------------------

def cmd_wait(run):
    targets = {"monolith": "http://monolith:8080/actuator/health",
               "account-service": "http://account-service:8082/actuator/health",
               "api-gateway": "http://api-gateway:8080/actuator/health",
               "nginx": BASE + "/actuator/health"}
    deadline = time.time() + 600
    pending = dict(targets)
    while pending and time.time() < deadline:
        for name, url in list(pending.items()):
            try:
                with urllib.request.urlopen(url, timeout=5) as r:
                    if r.status == 200:
                        print(f"  {name} is up")
                        del pending[name]
            except Exception:
                pass
        if pending:
            time.sleep(3)
    if pending:
        print("  timed out waiting for: " + ", ".join(pending))
        sys.exit(1)


def cmd_seed(run):
    run.phase = "seed"
    tag = os.path.basename(os.path.dirname(run.dir.rstrip("/"))).lower() + "-" + os.path.basename(run.dir.rstrip("/"))
    users = {}
    for name in ("u1", "u2", "u3"):
        email = f"{name}.{tag}@exp.test"
        status, resp = run.http("POST", "/api/users/register",
                                {"firstName": name.upper(), "lastName": "Exp", "email": email, "password": PASSWORD})
        users[name] = {"email": email, "id": resp.get("id") if isinstance(resp, dict) else None}
    run.state["users"] = users
    run.login_all()

    accts = {}
    for name, u in users.items():
        accts[name] = {}
        for t in ("CHECKING", "SAVINGS"):
            status, resp = run.http("POST", "/api/accounts", {"accountType": t}, token=u["token"])
            accts[name][t] = slim(resp)
    run.state["seed_accounts"] = accts

    u1c, u2c, u3c = accts["u1"]["CHECKING"]["id"], accts["u2"]["CHECKING"]["id"], accts["u3"]["CHECKING"]["id"]
    run.http("POST", "/api/transactions/deposit", {"accountId": u1c, "amount": 1000, "description": "seed"}, token=users["u1"]["token"])
    run.http("POST", "/api/transactions/deposit", {"accountId": u2c, "amount": 500, "description": "seed"}, token=users["u2"]["token"])
    run.http("POST", "/api/transactions/deposit", {"accountId": u3c, "amount": 250, "description": "seed"}, token=users["u3"]["token"])
    run.http("POST", "/api/transactions/withdraw", {"accountId": u1c, "amount": 100, "description": "seed"}, token=users["u1"]["token"])
    run.http("POST", "/api/transactions/transfer", {"sourceAccountId": u1c, "targetAccountId": u2c, "amount": 200, "description": "seed"}, token=users["u1"]["token"])

    run.state["view_seed"] = run.view()
    run.save()
    print("  seed view: " + json.dumps({k: [(a["accountNumber"], a["balance"]) for a in v["accounts"]] for k, v in run.state["view_seed"].items()}))


def cmd_forward(run):
    run.phase = "forward"
    run.login_all()
    run.state["view_forward"] = run.view()
    seed, fwd = run.state["view_seed"], run.state["view_forward"]
    run.state["forward_served_by"] = sorted({v["served_by"] for v in fwd.values()} - {None})
    run.state["forward_consistent"] = all(
        sorted(map(json.dumps, seed[u]["accounts"])) == sorted(map(json.dumps, fwd[u]["accounts"])) for u in seed)
    run.save()
    print(f"  served by {run.state['forward_served_by']}; forward view equals seed view: {run.state['forward_consistent']}")


def cmd_workload(run):
    run.phase = "workload"
    users = run.state["users"]
    w = {}
    status, resp = run.http("POST", "/api/accounts", {"accountType": "SAVINGS"}, token=users["u1"]["token"])
    w["X"] = {"owner": "u1", "http": status, **(slim(resp) if status == 201 else {})}
    status, resp = run.http("POST", "/api/accounts", {"accountType": "CHECKING"}, token=users["u2"]["token"])
    w["Y"] = {"owner": "u2", "http": status, **(slim(resp) if status == 201 else {})}
    z_id = run.state["seed_accounts"]["u3"]["SAVINGS"]["id"]
    status, resp = run.http("DELETE", f"/api/accounts/{z_id}", token=users["u3"]["token"])
    w["Z"] = {"owner": "u3", "http": status, "id": z_id, **({"closedStatus": resp.get("status")} if isinstance(resp, dict) else {})}
    run.state["workload"] = w
    run.state["view_workload"] = run.view()
    run.save()
    print("  workload: " + json.dumps({k: (v.get("accountNumber") or v.get("id"), v["http"]) for k, v in w.items()}))


def cmd_verify(run):
    run.phase = "verify"
    users = run.state["users"]
    w = run.state.get("workload")
    run.login_all()
    view = run.view()
    run.state["view_rollback"] = view
    v = {"logins_ok": all(u["login_status"]["verify"] == 200 for u in users.values()),
         "served_by": sorted({x["served_by"] for x in view.values()} - {None})}

    # Seed-era balances unchanged? (no money moved in the service era, so they must be)
    v["seed_balances_intact"] = all(
        find(view[u], a["id"]) is not None and find(view[u], a["id"])["balance"] == a["balance"]
        for u in users for a in run.state["view_seed"][u]["accounts"])

    if w:
        x, y, z = w["X"], w["Y"], w["Z"]
        v["X_visible"] = find(view["u1"], x.get("id")) is not None
        v["Y_visible"] = find(view["u2"], y.get("id")) is not None
        z_now = find(view["u3"], z["id"])
        v["Z_status_after_rollback"] = z_now["status"] if z_now else None
        v["service_era_writes"] = 3
        v["service_era_writes_visible"] = int(v["X_visible"]) + int(v["Y_visible"]) + int(v["Z_status_after_rollback"] == "CLOSED")

        # u1 still holds X's id from the service era and asks for its balance
        status, _ = run.http("GET", f"/api/accounts/{x['id']}/balance", token=users["u1"]["token"])
        v["X_balance_status"] = status

        # Closed account Z: can money still go in?
        status, _ = run.http("POST", "/api/transactions/deposit", {"accountId": z["id"], "amount": 10, "description": "verify: deposit into closed account"}, token=users["u3"]["token"])
        v["deposit_into_closed_account_status"] = status
        v["closed_account_accepts_money"] = status == 201

    # Monolith opens a new account for u2: which id / number does it get?
    status, resp = run.http("POST", "/api/accounts", {"accountType": "CHECKING"}, token=users["u2"]["token"])
    r = slim(resp) if status == 201 else {}
    v["new_account"] = {"http": status, **r}
    if w and r:
        clash = next((k for k in ("X", "Y") if w[k].get("accountNumber") == r["accountNumber"]), None)
        v["account_number_reused"] = clash is not None
        v["reused_from"] = f"{clash} (owner {w[clash]['owner']})" if clash else None
        v["reused_across_owners"] = clash is not None and w[clash]["owner"] != "u2"

        # u1 deposits into X by the id it got in the service era (deposit has no ownership check)
        status, _ = run.http("POST", "/api/transactions/deposit", {"accountId": x["id"], "amount": 5, "description": "verify: deposit by service-era id"}, token=users["u1"]["token"])
        v["deposit_by_service_era_id_status"] = status
        after = run.view()
        landed_owner = next((u for u in users if find(after[u], x["id"])), None)
        v["deposit_landed_in"] = landed_owner
        v["misdirected_deposit"] = status == 201 and landed_owner not in (None, "u1")

    run.state["verify"] = v
    run.save()
    print("  verify: " + json.dumps(v))


def cmd_signals(run):
    run.phase = "signals"
    s = {}
    status, resp = run.http("GET", "/actuator/health")
    s["monolith_health"] = resp.get("status") if isinstance(resp, dict) else status
    status, resp = run.http("GET", "/actuator/health", base="http://api-gateway:8080")
    s["gateway_health"] = resp.get("status") if isinstance(resp, dict) else status
    status, resp = run.http("GET", "/actuator/health", base="http://account-service:8082")
    s["account_service_health"] = resp.get("status") if isinstance(resp, dict) else status
    status, resp = run.http("GET", "/api/v1/targets", base=PROM)
    s["prometheus_targets"] = [{"job": t["labels"]["job"], "health": t["health"]}
                               for t in resp["data"]["activeTargets"]] if status == 200 else status
    q = urllib.parse.quote('sum by (status) (http_server_requests_seconds_count{job="banking-monolith"})')
    status, resp = run.http("GET", f"/api/v1/query?query={q}", base=PROM)
    s["prometheus_monolith_requests_by_status"] = {r["metric"].get("status"): float(r["value"][1])
                                                   for r in resp["data"]["result"]} if status == 200 else status
    run.state["signals"] = s
    run.save()
    print("  signals: " + json.dumps(s))


def read_kv(path):
    out = {}
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                if "=" in line:
                    k, v = line.rstrip("\n").split("=", 1)
                    out[k] = v
    return out


def cmd_summarize(run):
    meta = read_kv(os.path.join(run.dir, "meta.env"))
    codes = {}
    with open(os.path.join(run.dir, "http.jsonl")) as f:
        for line in f:
            e = json.loads(line)
            codes.setdefault(e["phase"], Counter())[str(e["status"])] += 1
    st = run.state
    v = st.get("verify", {})
    post = codes.get("verify", Counter())
    summary = {
        "condition": meta.get("condition"), "run": meta.get("run"), "commit": meta.get("commit"),
        "switch_to_blue_exit": meta.get("switch_blue_exit"), "rollback_exit": meta.get("switch_monolith_exit"),
        "nginx_routes_after_rollback": meta.get("nginx_after_rollback"),
        "forward_served_by": st.get("forward_served_by"), "forward_consistent": st.get("forward_consistent"),
        "verify": v, "signals": st.get("signals"),
        "log_lines_since_rollback": {k: int(val) for k, val in meta.items() if k.startswith(("error_", "warn_"))},
        "db": {k: val for k, val in meta.items() if k.startswith("db_")},
        "http_status_by_phase": {p: dict(c) for p, c in codes.items()},
        "post_rollback_5xx": sum(n for c, n in post.items() if c.startswith("5")),
        "post_rollback_4xx": sum(n for c, n in post.items() if c.startswith("4")),
    }
    with open(os.path.join(run.dir, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    print(f"  wrote {run.dir}/summary.json")


def cmd_aggregate(results_dir):
    rows = []
    for cond in sorted(os.listdir(results_dir)):
        cdir = os.path.join(results_dir, cond)
        if not os.path.isdir(cdir):
            continue
        for r in sorted(os.listdir(cdir)):
            p = os.path.join(cdir, r, "summary.json")
            if os.path.exists(p):
                with open(p) as f:
                    rows.append(json.load(f))

    def g(d, *keys):
        for k in keys:
            d = d.get(k) if isinstance(d, dict) else None
        return "–" if d is None else d

    head = ["cond", "run", "rollback exit", "health", "prom targets", "ERROR", "WARN",
            "5xx after", "4xx after", "service-era writes visible", "closed acct takes money",
            "acct no. reused", "misdirected deposit", "rows only in account_schema"]
    lines = ["# Rollback-loss results", "",
             "Generated by `harness/rollback.py aggregate`. One row per run; details in each run's summary.json.", "",
             "| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for s in rows:
        logs = s["log_lines_since_rollback"]
        targets = g(s, "signals", "prometheus_targets")
        tstr = ",".join(f"{t['job']}:{t['health']}" for t in targets) if isinstance(targets, list) else targets
        vis = g(s, "verify", "service_era_writes_visible")
        tot = g(s, "verify", "service_era_writes")
        lines.append("| " + " | ".join(str(x) for x in [
            s["condition"], s["run"], s["rollback_exit"], g(s, "signals", "monolith_health"), tstr,
            logs.get("error_monolith", "–"), logs.get("warn_monolith", "–"),
            s["post_rollback_5xx"], s["post_rollback_4xx"],
            f"{vis}/{tot}" if tot != "–" else "–",
            g(s, "verify", "closed_account_accepts_money"), g(s, "verify", "reused_from"),
            g(s, "verify", "misdirected_deposit"), g(s, "db", "db_rows_only_in_account_schema"),
        ]) + " |")
    out = os.path.join(results_dir, "summary.md")
    with open(out, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"  wrote {out}")


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    cmd, target = sys.argv[1], sys.argv[2]
    if cmd == "aggregate":
        return cmd_aggregate(target)
    run = Run(target)
    {"wait": cmd_wait, "seed": cmd_seed, "forward": cmd_forward, "workload": cmd_workload,
     "verify": cmd_verify, "signals": cmd_signals, "summarize": cmd_summarize}[cmd](run)


if __name__ == "__main__":
    main()
