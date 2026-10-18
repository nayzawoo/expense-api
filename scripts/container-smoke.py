#!/usr/bin/env python3
"""Exercise disposable Compose containers; never use the development project volume."""
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
PROJECT = "expense-smoke-" + uuid.uuid4().hex[:12]
ENV = {**os.environ, "DATABASE_PASSWORD": "smoke-only-password"}
COMPOSE = ["docker", "compose", "--project-name", PROJECT, "--file", str(ROOT / "compose.yaml")]


def run(*args, capture=True):
    return subprocess.run(args, cwd=ROOT, env=ENV, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def compose(*args, capture=True):
    return run(*COMPOSE, *args, capture=capture)


def inspect(container):
    return json.loads(run("docker", "inspect", container))[0]


def health(group):
    result = compose("exec", "-T", "api", "curl", "--silent", "--show-error", "--max-time", "8",
                     "--write-out", "\n%{http_code}",
                     "http://localhost:8080/actuator/health/" + group)
    body, code = result.rsplit("\n", 1)
    return int(code), json.loads(body)


def wait_for(predicate, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(2)
    raise AssertionError("Timed out waiting for container health transition")


def sql(statement):
    return compose("exec", "-T", "postgres", "psql", "-U", "expense", "-d", "expense",
                   "-v", "ON_ERROR_STOP=1", "-At", "-c", statement).strip()


config = json.loads(compose("--profile", "*", "config", "--format", "json"))
assert set(config["services"]) == {"api", "postgres"}
for service in config["services"].values():
    assert not service.get("ports"), "Host-published ports are forbidden"
    assert service.get("network_mode") != "host"
    assert set(service["networks"]) == {"backend"}
assert config["networks"]["backend"]["internal"] is True
assert config["volumes"]["expense-postgres"]["name"] == PROJECT + "_expense-postgres"
assert not config["volumes"]["expense-postgres"].get("external")
assert config["services"]["postgres"]["image"] == "postgres:18.6"
assert config["services"]["api"]["environment"]["DATABASE_URL"] == "jdbc:postgresql://postgres:5432/expense"
assert config["services"]["api"]["read_only"] is True

try:
    compose("up", "--build", "--detach", "--wait", "--wait-timeout", "180", capture=False)
    api = compose("ps", "--quiet", "api").strip()
    postgres = compose("ps", "--quiet", "postgres").strip()
    for container in (api, postgres):
        state = inspect(container)
        assert not state["HostConfig"].get("PortBindings")
        networks = state["NetworkSettings"]["Networks"]
        assert set(networks) == {PROJECT + "_backend"}
    network = json.loads(run("docker", "network", "inspect", PROJECT + "_backend"))[0]
    assert network["Internal"] is True
    assert compose("exec", "-T", "api", "id", "-u").strip() == "10001"
    assert inspect(api)["HostConfig"]["ReadonlyRootfs"] is True
    assert health("readiness") == (200, {"status": "UP"})
    assert health("liveness") == (200, {"status": "UP"})
    for endpoint in ("env", "configprops", "beans", "metrics", "heapdump"):
        code = compose("exec", "-T", "api", "curl", "--silent", "--max-time", "8", "--output", "/dev/null",
                       "--write-out", "%{http_code}", "http://localhost:8080/actuator/" + endpoint)
        assert code == "401", "Diagnostic endpoint exposed: " + endpoint
    sql("CREATE TABLE container_smoke_marker (value integer); INSERT INTO container_smoke_marker VALUES (42)")
    compose("stop", "postgres", capture=False)
    wait_for(lambda: health("readiness") == (503, {"status": "DOWN"}))
    assert health("liveness") == (200, {"status": "UP"})
    wait_for(lambda: inspect(api)["State"]["Health"]["Status"] == "unhealthy")
    assert inspect(api)["State"]["Running"] is True
    compose("start", "postgres", capture=False)
    wait_for(lambda: health("readiness") == (200, {"status": "UP"}))
    wait_for(lambda: inspect(api)["State"]["Health"]["Status"] == "healthy")
    assert health("liveness") == (200, {"status": "UP"})
    assert sql("SELECT value FROM container_smoke_marker") == "42"
    compose("down", capture=False)
    compose("up", "--detach", "--wait", "--wait-timeout", "180", capture=False)
    assert sql("SELECT value FROM container_smoke_marker") == "42"
    assert health("readiness") == (200, {"status": "UP"})
    print("PASS: private networking, non-root/read-only runtime, restricted health, DB outage/recovery, persistent volume")
finally:
    # Only this randomly named, script-owned disposable project is deleted.
    compose("down", "--volumes", capture=False)
