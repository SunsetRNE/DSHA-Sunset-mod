"""Verify build377 against committed Git objects. Run with Python 3 from any directory in this clone."""
import hashlib
import json
import posixpath
import re
import subprocess
from pathlib import Path
from urllib.parse import unquote, urlsplit


DOCS = "docs/releases/build377"
LINK = re.compile(r"!?\[[^\]\n]*\]\((<[^>\n]+>|(?:[^()\s\n]|\([^()\n]*\))+)\)")
ROOT = Path(__file__).resolve().parents[3]


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def files(ref):
    entries = {}
    for row in git("ls-tree", "-rz", ref).split(b"\0"):
        if row:
            info, name = row.split(b"\t", 1)
            mode, kind, oid = info.decode("ascii").split()
            entries[name.decode("utf8")] = (mode, kind, oid)
    return entries


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def main():
    current = files("HEAD")
    record = json.loads(git("show", "HEAD:" + DOCS + "/publication.json"))
    signed_tree = git("rev-parse", record["publicSourceCommit"] + "^{tree}").decode().strip()
    require(signed_tree == record["signedTree"], "The source tree does not match the signing tree.")
    baseline = files(record["publicSourceCommit"])
    require(len(baseline) == record["sourceFiles"], "Source file count differs.")
    for path, entry in baseline.items():
        require(current.get(path) == entry, "Source file or mode differs: " + path)
    paths = set(current)
    directories = {"."}
    for path in paths:
        parts = path.split("/")
        directories.update("/".join(parts[:n]) for n in range(1,len(parts)))
    records = record["materials"]
    reports = {item["path"] for item in records if item["kind"] == "report"}
    require(len(reports) == record["originalReports"], "Report count differs.")
    protected = {item["path"] for item in records}
    markdown = reports | {DOCS + "/README.md", DOCS + "/local-evidence.md", ".github/README.md"}
    targets = sorted(protected | markdown)
    contents = {}
    child = subprocess.Popen(["git", "-C", str(ROOT), "cat-file", "--batch"],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    try:
        for name in targets:
            require(name in current, "Missing file: " + name)
            child.stdin.write(current[name][2].encode("ascii") + b"\n")
            child.stdin.flush()
            header=child.stdout.readline().split()
            data=child.stdout.read(int(header[-1]))
            require(child.stdout.read(1)==b"\n", "Git object read failed.")
            contents[name]=(hashlib.sha256(data).hexdigest(),len(data),data if name in markdown else None)
    finally:
        child.stdin.close()
        code=child.wait()
        require(code==0,"Git object reader failed.")
    for item in records:
        actual=contents[item["path"]]
        require(actual[:2]==(item["sha256"],item["bytes"]),"Checksum differs: "+item["path"])
    local_links=0
    for name in sorted(markdown):
        for match in LINK.finditer(contents[name][2].decode("utf8")):
            dest=urlsplit(match[1].strip("<>"))
            if dest.scheme in ("https","http","mailto") or dest.netloc:
                continue
            require(not dest.scheme, "Local drive in Markdown link: " + name)
            path=posixpath.normpath(posixpath.join(posixpath.dirname(name),unquote(dest.path))) if dest.path else name
            require(path in paths or path in directories,"Broken local link: "+name+" -> "+path)
            fragment=unquote(dest.fragment)
            if fragment.startswith("local-"):
                require(path in contents and ('id="'+fragment+'"').encode() in contents[path][2],"Missing retention entry: "+fragment)
            elif re.fullmatch(r"L\d+",fragment):
                line=int(fragment[1:])
                require(0 < line <= len(git("show","HEAD:"+path).splitlines()),"Invalid source line: "+path+"#"+fragment)
            local_links+=1
    print(json.dumps(dict(sourceFiles=len(baseline),signedTree=signed_tree,originalReports=len(reports),
                          uniquelyHashedMaterialFiles=len(protected),verifiedLocalMarkdownLinks=local_links,
                          status="PASS"),indent=2))


if __name__ == "__main__":
    main()
