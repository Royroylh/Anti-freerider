#!/usr/bin/env python3
"""Independently recompute contribution percentages from a downloaded export.

The server writes a JSON appendix containing only accepted task points and frozen
author shares. This script parses that appendix, recomputes the numbers itself,
and compares them with the visible report. It does not use any server-side
calculation code.

Usage:
    python3 scripts/check_contributions.py
    python3 scripts/check_contributions.py --project "Contribution Math 100"
"""
from __future__ import annotations

import argparse
import html as html_module
import http.cookiejar
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("AF_BASE_URL", "http://127.0.0.1:8765")
EMAIL = os.environ.get("AF_EMAIL", "alice@demo.edu")
PASSWORD = os.environ.get("AF_PASSWORD", "demo-pass-123")


class Session:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def request(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json"} if body is not None else {}
        request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request) as response:
                return response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            raise SystemExit(f"{method} {path} -> {error.code}: {error.read().decode('utf-8', errors='replace')}")

    def json(self, method, path, body=None):
        return json.loads(self.request(method, path, body))


def fmt(value):
    return str(int(value)) if abs(value - round(value)) < 0.001 else f"{value:.1f}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", default=os.environ.get("AF_PROJECT", "Contribution Math 100"))
    parser.add_argument("--save", default=str(ROOT / "build" / "verification" / "recomputed-report.html"))
    args = parser.parse_args()

    session = Session()
    session.json("POST", "/api/auth/login", {"email": EMAIL, "password": PASSWORD, "remember": True})
    projects = session.json("GET", "/api/projects")["projects"]
    project = next((p for p in projects if p["name"] == args.project), None)
    if project is None:
        raise SystemExit(f"Project not found for {EMAIL}: {args.project}")

    html = session.request(
        "GET", f"/api/projects/{project['id']}/report/export?tasks=1&timeline=1&disputes=1&reminders=1")
    save = Path(args.save)
    save.parent.mkdir(parents=True, exist_ok=True)
    save.write_text(html, encoding="utf-8")

    match = re.search(r'<script type="application/json" id="report-data">(.*?)</script>', html, re.S)
    if not match:
        raise SystemExit("No report-data appendix found")
    data = json.loads(html_module.unescape(match.group(1)))
    appendix = data["dataAppendix"]
    planned = appendix["projectPlannedPoints"]
    accepted = 0
    member_points: dict[str, float] = {}
    for score in appendix["scores"]:
        accepted += score["points"]
        for share in score["shares"]:
            member_points[share["name"]] = member_points.get(share["name"], 0.0) + \
                score["points"] * share["sharePct"] / 100.0

    print(f"Project: {args.project}  (export: {save})")
    print(f"Recomputed project completion: {accepted} / {planned} = {fmt(round(accepted / planned * 100, 1))}%")
    for name, points in member_points.items():
        share = round(points / accepted * 100, 1) if accepted else 0
        print(f"  {name}: {fmt(points)} points = {fmt(share)}% of accepted work")

    if args.project == "Contribution Math 100":
        expected = {"Alice Chen": (15.0, 25.0), "Ben Wu": (5.0, 8.3),
                    "Cara Lin": (40.0, 66.7), "Dan Zhou": (0.0, 0.0)}
        assert accepted == 60 and planned == 100, (accepted, planned)
        for name, (points, share) in expected.items():
            actual_points = round(member_points.get(name, 0.0), 1)
            actual_share = round(member_points.get(name, 0.0) / accepted * 100, 1) if accepted else 0
            assert actual_points == points, (name, actual_points, points)
            assert actual_share == share, (name, actual_share, share)
        zero = next(member for member in data["contribution"]["members"] if member["name"] == "Dan Zhou")
        assert zero["hasAccepted"] and zero["points"] == 0 and zero["sharePct"] == 0, zero
        for visible in ("60%", "25%", "15", "暂无已验收贡献", "Delivered work", "Not yet delivered"):
            if visible not in html:
                raise SystemExit(f"Export is missing visible value: {visible}")
        print("Known-number assertions passed: 60%, Alice 15 points / 25%, Cara 40 points, Dan 0 points / 0%, empty branch labelled.")
    no_votes = all(token not in html.lower() for token in ("choice", "voter", "disagree"))
    assert no_votes, "Export contains an anonymous ballot field"
    print("No vote fields present:", no_votes)


if __name__ == "__main__":
    main()
