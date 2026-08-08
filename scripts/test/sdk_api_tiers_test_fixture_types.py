"""Initial-preview type fixture sources for SDK API tier tests."""
from __future__ import annotations

from sdk_api_tiers_test_constants import INITIAL_TYPE_ROOTS


_WRITE_PACKAGE_UNMARKED = {
    "historical-package-promotion-unmarked",
    "historical-package-promotion-annotation",
    "historical-package-promotion-with-new-preview",
}


def initial_sources(current: bool, variant: str) -> dict[str, str]:
    result = {}
    for root in INITIAL_TYPE_ROOTS:
        if omit_root(current, variant, root):
            continue
        relative, text = source_for_internal(root, current=current, variant=variant)
        result[relative] = text
    return result


def omit_root(current: bool, variant: str, root: str) -> bool:
    if not current:
        return False
    if variant == "historical-preview-root-removed" and root.endswith("/TransactionStatus"):
        return True
    return variant == "historical-preview-package-removed" and root.rpartition("/")[0] == "dev/turboism/sdk/cubism/write"


def source_for_type(internal_name: str, *, current: bool, variant: str) -> tuple[str, str]:
    package, _, _simple = internal_name.rpartition("/")
    marker = "@dev.turboism.sdk.PreviewApi\n" if current else ""
    body = manager_body("public String value() { return \"preview\"; }") if internal_name.endswith("/TransactionManager") else "public String value() { return \"preview\"; }"
    if variant == "initial-promotion-change" and current and internal_name.endswith("/TransactionManager"):
        body = manager_body("public int value() { return 1; }")
    return f"dev/turboism/sdk/{package.removeprefix('dev/turboism/sdk/') if package.startswith('dev/turboism/sdk/') else package}.java", ""


def source_for_internal(internal_name: str, *, current: bool, variant: str) -> tuple[str, str]:
    package = internal_name.rpartition("/")[0].replace("/", ".")
    simple = internal_name.rpartition("/")[2]
    marker = "" if unmarked(current, variant, internal_name) else "@dev.turboism.sdk.PreviewApi\n"
    body = type_body(current, variant, internal_name)
    return internal_name + ".java", f"""package {package};
{marker}public class {simple} {{
    {body}
}}
"""


def unmarked(current: bool, variant: str, internal_name: str) -> bool:
    if not current:
        return True
    transaction_manager = internal_name.endswith("/TransactionManager")
    write_package = internal_name.rpartition("/")[0] == "dev/turboism/sdk/cubism/write"
    return (transaction_manager and variant in {"initial-promotion-unmarked", "initial-promotion-change"}) or (write_package and variant in _WRITE_PACKAGE_UNMARKED)


def type_body(current: bool, variant: str, internal_name: str) -> str:
    if not internal_name.endswith("/TransactionManager"):
        return "public String value() { return \"preview\"; }"
    if current and variant == "initial-promotion-change":
        return promoted_manager_body()
    return manager_body(mutable_member(current, variant))


def promoted_manager_body() -> str:
    return """public int value() { return 1; }
    public static class NestedStable {
        public String nested() { return \"stable\"; }
    }"""


def manager_body(mutable_member: str) -> str:
    return f"""public String value() {{ return \"preview\"; }}
    {mutable_member}
    public static class NestedStable {{
        public String nested() {{ return \"stable\"; }}
    }}"""


def mutable_member(current: bool, variant: str) -> str:
    if current and variant == "historical-preview-member-removed":
        return ""
    if current and variant == "historical-preview-member-descriptor-change":
        return "public int mutableMember() { return 1; }"
    return "public String mutableMember() { return \"preview\"; }"
