"""Shared constants for the SDK API tier mutation matrix."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "scripts/test/sdk_api_baseline_cli.py"
LEDGER = ROOT / "docs/sdk/baselines/sdk-api-initial-preview-v1.json"
COMMIT = "0123456789abcdef0123456789abcdef01234567"
PREFIX = "dev.turboism.sdk"

INITIAL_TYPE_ROOTS = (
    "dev/turboism/sdk/cubism/transaction/CommitFailedException",
    "dev/turboism/sdk/cubism/transaction/DocumentId",
    "dev/turboism/sdk/cubism/transaction/ModelTransaction",
    "dev/turboism/sdk/cubism/transaction/PermissionDeniedException",
    "dev/turboism/sdk/cubism/transaction/RollbackFailedException",
    "dev/turboism/sdk/cubism/transaction/TransactionAlreadyActiveException",
    "dev/turboism/sdk/cubism/transaction/TransactionClosedException",
    "dev/turboism/sdk/cubism/transaction/TransactionException",
    "dev/turboism/sdk/cubism/transaction/TransactionManager",
    "dev/turboism/sdk/cubism/transaction/TransactionRequiredException",
    "dev/turboism/sdk/cubism/transaction/TransactionStatus",
    "dev/turboism/sdk/cubism/transaction/WriteValidationException",
    "dev/turboism/sdk/cubism/write/CubismWriteCommand",
    "dev/turboism/sdk/cubism/write/WriteCanvasCommand",
    "dev/turboism/sdk/cubism/write/WriteClipMaskCommand",
    "dev/turboism/sdk/cubism/write/WriteModelObjectCommand",
    "dev/turboism/sdk/cubism/write/WriteParameterCommand",
    "dev/turboism/sdk/cubism/write/WriteResult",
    "dev/turboism/sdk/cubism/mesh/MeshWriteCommand",
    "dev/turboism/sdk/cubism/mesh/MirrorWritebackCommand",
    "dev/turboism/sdk/cubism/deformer/DeformerWriteCommand",
    "dev/turboism/sdk/cubism/psd/PsdBindingWriteCommand",
    "dev/turboism/sdk/cubism/boundingbox/BoundingBoxWriteCommand",
    "dev/turboism/sdk/cubism/event/ProjectLifecycleEvent",
    "dev/turboism/sdk/cubism/event/RenderStatusChangedEvent",
    "dev/turboism/sdk/cubism/event/SelectionChangedEvent",
    "dev/turboism/sdk/cubism/event/TextureAtlasReinitEvent",
)

INITIAL_METHOD_ROOT = {
    "target": "method",
    "owner": "dev/turboism/sdk/cubism/CubismFacade",
    "name": "transactionManager",
    "descriptor": "()Ldev/turboism/sdk/cubism/transaction/TransactionManager;",
}
