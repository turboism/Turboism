"""Stable compatibility façade for deterministic SDK API canonicalization."""
from __future__ import annotations

import hashlib
import struct
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

from sdk_api_baseline_annotation_attributes import parse_attributes, parse_type_annotations
from sdk_api_baseline_annotation_values import annotation_value, parse_annotation, parse_annotations, parse_parameter_annotations, target_info, type_path
from sdk_api_baseline_common import (
    ACC_ABSTRACT, ACC_ANNOTATION, ACC_BRIDGE, ACC_ENUM, ACC_FINAL, ACC_INTERFACE,
    ACC_MODULE, ACC_NATIVE, ACC_PRIVATE, ACC_PROTECTED, ACC_PUBLIC, ACC_STATIC,
    ACC_STRICT, ACC_SUPER, ACC_SYNCHRONIZED, ACC_SYNTHETIC, ACC_TRANSIENT,
    ACC_VARARGS, ACC_VOLATILE, API_VISIBILITY, FORBIDDEN_API_TOKENS,
    GENERATOR_VERSION, HEADER, PREVIEW_ANNOTATION_DESCRIPTOR, SCHEMA_VERSION,
    BaselineError, encode_float32_bits, encode_float64_bits, encode_list,
    encode_name, encode_string, sha256_bytes, sha256_file, utf16_units,
)
from sdk_api_baseline_identity import canonical_identity, split_canonical_record
from sdk_api_baseline_model import Annotation, Attributes, ClassInfo, ConstantPool, FieldInfo, MethodInfo, ParsedClass, Reader, RecordComponent, TierMarkerFacts, TypeAnnotation
from sdk_api_baseline_parse import effective_class_access, load_parsed_classes, parse_class_with_pool
from sdk_api_baseline_records import canonical_dump, canonical_records, check_forbidden
from sdk_api_baseline_render import annotations_value, class_flags, encode_constant, field_flags, method_flags, parameter_annotations_value
from sdk_api_baseline_tiers_view import canonical_records_for_tiers


def normalized_record(record: str) -> str:
    return record


def preview_marker_present(record: str) -> bool:
    _kind, values = split_canonical_record(record)
    return PREVIEW_ANNOTATION_DESCRIPTOR in values.get("annotations", "")
