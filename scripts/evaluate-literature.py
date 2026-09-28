"""Repeatable local literature API evaluation; writes responses under ignored .local/."""

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CASES = ROOT / "docs" / "literature-eval-cases.json"


def request_json(url, payload=None, timeout=30):
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Accept": "application/json", "Content-Type": "application/json; charset=utf-8"},
        method="GET" if body is None else "POST",
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            result = json.load(response)
            return response.status, round((time.monotonic() - started) * 1000), result
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            result = json.loads(raw)
        except ValueError:
            result = {"message": raw.decode("utf-8", errors="replace")}
        return error.code, round((time.monotonic() - started) * 1000), result
    except (OSError, TimeoutError) as error:
        return 0, round((time.monotonic() - started) * 1000), {"message": str(error)}


def label(documents, fragment):
    matches = [doc["id"] for doc in documents if fragment in doc["title"]]
    if len(matches) != 1:
        raise ValueError(f"Expected one document title containing {fragment!r}; found {len(matches)}")
    return matches[0]


def run_search(base, project_id, documents, case):
    query = urllib.parse.urlencode({"projectId": project_id, "q": case["query"], "limit": 5, "translate": "false"})
    http, elapsed, response = request_json(f"{base}/api/literature/search?{query}")
    hits = response.get("hits", []) if http == 200 else []
    expected_id = label(documents, case["documentTitleContains"]) if "documentTitleContains" in case else None
    doc_rank = next((i for i, hit in enumerate(hits, 1) if hit["documentId"] == expected_id), None)
    page_rank = next((i for i, hit in enumerate(hits, 1)
                      if hit["documentId"] == expected_id and hit["pageNumber"] == case["page"]), None) \
        if "page" in case else None
    if case.get("expectedNoHits"):
        passed = http == 200 and not hits
    else:
        passed = http == 200 and doc_rank == 1 and ("page" not in case or page_rank is not None)
    return {
        "id": case["id"], "http": http, "ms": elapsed, "pass": passed,
        "docRank": doc_rank, "pageRank": page_rank, "semanticStatus": response.get("semanticStatus"),
        "hits": [{"title": hit["title"], "page": hit["pageNumber"], "score": hit["score"]} for hit in hits],
        "error": response.get("message") if http != 200 else None,
    }


def mostly_chinese(text):
    han = len(re.findall(r"[\u3400-\u9fff]", text))
    latin = len(re.findall(r"[A-Za-z]", text))
    return han >= 2 and han * 3 >= latin


def run_answer(base, project_id, case):
    http, elapsed, response = request_json(
        f"{base}/api/literature/answer",
        {"projectId": project_id, "question": case["question"]}, timeout=210,
    )
    citations = response.get("citations", []) if http == 200 else []
    zh, en = response.get("answerZh", ""), response.get("answerEn", "")
    passed = http == 200 and response.get("status") == case["expectedStatus"]
    if passed and case["expectedStatus"] == "GENERATED_UNVERIFIED":
        zh_refs = set(re.findall(r"\[C\d+\]", zh))
        en_refs = set(re.findall(r"\[C\d+\]", en))
        passed = mostly_chinese(zh) and not mostly_chinese(en) and bool(zh_refs) and zh_refs == en_refs
        if "requiredCitationDocumentTitleContains" in case:
            passed &= any(
                case["requiredCitationDocumentTitleContains"] in hit["title"]
                and ("requiredCitationPage" not in case or hit["pageNumber"] == case["requiredCitationPage"])
                and f"[C{i}]" in zh_refs
                for i, hit in enumerate(citations, 1)
            )
        passed &= all(term in zh for term in case.get("requiredTermsZh", []))
        passed &= all(term.lower() in en.lower() for term in case.get("requiredTermsEn", []))
    return {
        "id": case["id"], "http": http, "ms": elapsed, "pass": bool(passed),
        "status": response.get("status"), "answerZh": zh, "answerEn": en,
        "citations": [{"title": hit["title"], "page": hit["pageNumber"], "score": hit["score"]} for hit in citations],
        "error": response.get("message") if http != 200 else None,
    }


def run_translation(base, project_id, documents, case):
    query = urllib.parse.urlencode({"projectId": project_id, "q": case["query"], "limit": 1, "translate": "true"})
    http, elapsed, response = request_json(f"{base}/api/literature/search?{query}", timeout=210)
    hits = response.get("hits", []) if http == 200 else []
    hit = hits[0] if hits else {}
    translation = hit.get("translation") or ""
    expected_id = label(documents, case["documentTitleContains"])
    passed = http == 200 and response.get("translationStatus") == "GENERATED_UNVERIFIED" \
        and hit.get("documentId") == expected_id and hit.get("pageNumber") == case["page"] \
        and all(term.lower() in translation.lower() for term in case.get("required", [])) \
        and all(term.lower() not in translation.lower() for term in case.get("forbidden", []))
    return {
        "id": case["id"], "http": http, "ms": elapsed, "pass": bool(passed),
        "status": response.get("translationStatus"), "page": hit.get("pageNumber"),
        "translation": translation, "error": response.get("message") if http != 200 else None,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:18085")
    parser.add_argument("--mode", choices=("search", "smoke", "full"), default="search")
    parser.add_argument("--ids", help="Comma-separated case IDs to run during a focused iteration")
    parser.add_argument("--strict", action="store_true", help="Exit nonzero if any case fails")
    args = parser.parse_args()
    selected = set(args.ids.split(",")) if args.ids else None
    base = args.base_url.rstrip("/")
    cases = json.loads(CASES.read_text(encoding="utf-8"))
    query = urllib.parse.urlencode({"projectId": args.project_id})
    documents = None
    for _ in range(10):
        http, _, documents = request_json(f"{base}/api/literature/documents?{query}")
        if http == 200 and isinstance(documents, list):
            break
        time.sleep(1)
    if http != 200 or not isinstance(documents, list):
        raise RuntimeError(f"Could not list project documents: HTTP {http}")
    search = []
    for case in cases["search"]:
        if selected is not None and case["id"] not in selected:
            continue
        result = run_search(base, args.project_id, documents, case)
        search.append(result)
        print(f"{'PASS' if result['pass'] else 'FAIL'} {result['id']}: "
              f"doc={result['docRank']} page={result['pageRank']}, {result['ms']} ms", flush=True)
    answer_cases = [] if args.mode == "search" else [
        case for case in cases["answer"] if (args.mode == "full" or not case.get("slow"))
        and (selected is None or case["id"] in selected)
    ]
    translations = []
    if args.mode == "full":
        for case in cases.get("translation", []):
            if selected is not None and case["id"] not in selected:
                continue
            result = run_translation(base, args.project_id, documents, case)
            translations.append(result)
            print(f"{'PASS' if result['pass'] else 'FAIL'} {result['id']}: "
                  f"{result['status'] or result['error']}, {result['ms']} ms", flush=True)
    answers = []
    for case in answer_cases:
        result = run_answer(base, args.project_id, case)
        answers.append(result)
        print(f"{'PASS' if result['pass'] else 'FAIL'} {result['id']}: "
              f"{result['status'] or result['error']}, {result['ms']} ms", flush=True)
    report = {"timestamp": datetime.now(timezone.utc).isoformat(), "projectId": args.project_id,
              "mode": args.mode, "search": search, "translation": translations, "answer": answers}
    output_dir = ROOT / ".local" / "literature-eval"
    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / f"{datetime.now().strftime('%Y%m%d-%H%M%S')}-{args.mode}.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Search {sum(x['pass'] for x in search)}/{len(search)}; "
          f"translation {sum(x['pass'] for x in translations)}/{len(translations)}; "
          f"answer {sum(x['pass'] for x in answers)}/{len(answers)}; report {path}")
    if args.strict and any(not item["pass"] for item in search + translations + answers):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
