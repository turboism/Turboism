"""Language policy contract: default documentation and comments are English.

Four axes matter here:

* prose in a default document fails, while a quoted native caption does not,
  because a host probe must match the captions a real editor renders;
* a localized product path is exempt, so translations never need an exception;
* a translated comment fails, while a comment quoting a native caption passes;
* a string literal is never read, because captions are data rather than prose.
"""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
SPEC = importlib.util.spec_from_file_location("doc_language_check", ROOT / "scripts/test/check_doc_language.py")
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)

ENGLISH_DOC = "# Title\n\nEvery default document is written in English.\n"
# A translated document, spelled with escapes so this test file itself stays English.
TRANSLATED_DOC = "# \u6807\u9898\n\n\u672c\u76ee\u5f55\u5b58\u653e\u53ef\u81ea\u52a8\u6267\u884c\u7684\u6d4b\u8bd5\u4ee3\u7801\uff0c\u4e0e\u4eba\u5de5\u63a2\u9488\u8bc1\u636e\u5206\u5f00\u7ba1\u7406\u3002\n"


class TreeFixture:
    """A throwaway tree holding only the files one case needs."""

    def __init__(self, **files):
        self._dir = tempfile.TemporaryDirectory()
        self.path = Path(self._dir.name)
        for name, text in files.items():
            target = self.path / name.replace("__", ".")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")

    def __enter__(self):
        return self.path

    def __exit__(self, *_):
        self._dir.cleanup()


def scanned(fixture: Path) -> list[str]:
    return [relative.as_posix() for relative, _ in CHECK.scan(fixture, [fixture])]


class DocumentPolicyTest(unittest.TestCase):
    def test_english_document_passes(self):
        with TreeFixture(**{"README.md": ENGLISH_DOC}) as tree:
            self.assertEqual([], scanned(tree))

    def test_translated_prose_fails(self):
        with TreeFixture(**{"README.md": TRANSLATED_DOC}) as tree:
            self.assertEqual(["README.md"], scanned(tree))

    def test_quoted_native_caption_is_not_prose(self):
        document = (
            "# Probe\n\n"
            "The probe matches the caption `确定` that the native save prompt renders.\n"
        )
        with TreeFixture(**{"README.md": document}) as tree:
            self.assertEqual([], scanned(tree))

    def test_localized_product_paths_are_exempt(self):
        with TreeFixture(**{
            "docs/zh/README.md": TRANSLATED_DOC,
            "plugins/demo/README_zh.md": TRANSLATED_DOC,
            "runtime/i18n/messages_zh_Hans.properties": "dialog.save=\u4fdd\u5b58\n",
        }) as tree:
            self.assertEqual([], scanned(tree))


class CodePolicyTest(unittest.TestCase):
    def test_translated_comment_fails(self):
        source = "class Probe {\n    // \u6821\u9a8c\u8282\u70b9\u987a\u5e8f\n    void run() {}\n}\n"
        with TreeFixture(**{"Probe.java": source}) as tree:
            self.assertEqual(["Probe.java"], scanned(tree))

    def test_english_comment_quoting_a_native_caption_passes(self):
        source = 'class Probe {\n    // Matches the native title "\u7f16\u8f91\u7eb9\u7406\u96c6" only.\n    void run() {}\n}\n'
        with TreeFixture(**{"Probe.java": source}) as tree:
            self.assertEqual([], scanned(tree))

    def test_native_caption_literal_passes(self):
        source = 'class Probe {\n    void run() {\n        match("\u65e0\u6cd5\u6253\u5f00\u7eb9\u7406\u56fe\u96c6\u7f16\u8f91\u5668\u7a97\u53e3");\n    }\n}\n'
        with TreeFixture(**{"Probe.java": source}) as tree:
            self.assertEqual([], scanned(tree))

    def test_translated_python_comment_fails(self):
        source = "def run():\n    # \u4ece\u5171\u4eab\u7f13\u5b58\u62c9\u53d6\u8bed\u6599\n    return None\n"
        with TreeFixture(**{"runner.py": source}) as tree:
            self.assertEqual(["runner.py"], scanned(tree))

    def test_python_hash_inside_a_literal_passes(self):
        source = (
            "HEADINGS = {\n    \"README_zh.md\": \"## \u8981\u6c42\u4e0e\u517c\u5bb9\u6027\",\n"
            "    \"README_ja.md\": \"## \u8981\u4ef6\u3068\u4e92\u63db\u6027\",\n}\n"
        )
        with TreeFixture(**{"strings.py": source}) as tree:
            self.assertEqual([], scanned(tree))


class RepositoryScopeTest(unittest.TestCase):
    def test_policy_scope_is_converted(self):
        root = CHECK.repo_root(ROOT)
        seeds = [root / name for name in CHECK.POLICY_SCOPE]
        self.assertEqual([], [relative.as_posix() for relative, _ in CHECK.scan(root, seeds)])


if __name__ == "__main__":
    unittest.main()
