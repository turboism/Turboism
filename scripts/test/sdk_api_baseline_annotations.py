"""Backward-compatible annotation parser exports."""
from sdk_api_baseline_annotation_attributes import parse_attributes, parse_type_annotations
from sdk_api_baseline_annotation_values import (
    annotation_value,
    parse_annotation,
    parse_annotations,
    parse_parameter_annotations,
    target_info,
    type_path,
)

__all__ = [
    "annotation_value",
    "parse_annotation",
    "parse_annotations",
    "parse_attributes",
    "parse_parameter_annotations",
    "parse_type_annotations",
    "target_info",
    "type_path",
]
