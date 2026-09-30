#!/usr/bin/env bash
# Core acquisition probe adapter for the generic exact-host runner.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-core-acquisition-host-validation.sh <installed-version-code> [run-label] [runner-options...]" >&2
  exit 2
fi

version="$1"
shift
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture "$version" || exit 2

case "$version" in
  5000)
    profile='cubism-5.0.00'
    editor_jar_sha256='42ee222a05478f48739bb1fbf7e7bc7350b21004e506e1b555d1d55c584d99cd'
    core_jar_sha256='9deda4c05a69549e23196bcf7981e9e5325e387c9b7beb468145e21467ac27b0'
    ;;
  5001)
    profile='cubism-5.0.01'
    editor_jar_sha256='1aa93b1d9d8da88e8c28de06b680c41a73fc70243307d856d4a2967e6e487a0b'
    core_jar_sha256='e8b99be96fddfe907e9c83878a6df5e9463a4d147b507f28e2aaaa973e2c090a'
    ;;
  5002)
    profile='cubism-5.0.02'
    editor_jar_sha256='39e73c8238acba5270422a6e0f228c66a733177d009c710721381285ab0af072'
    core_jar_sha256='0e0bfc521255d04bd48ba00879e6a03fc8e022a3e9a45b7662f4ae7f3e6a5042'
    ;;
  5003)
    profile='cubism-5.0.03'
    editor_jar_sha256='05bc9b872527fed3dc2641ff833b03d86e6fdafbe6faa294eb3d0afcc0f88a66'
    core_jar_sha256='f9bcef94c9b2a9c3ce6068f95499b0782af79bfe5fe6c66debb87a717e8b93ca'
    ;;
  5004)
    profile='cubism-5.0.04'
    editor_jar_sha256='7298c5731525a2bd5ac4e6d5246f1f08603651b0ec37cf5dc3e8ad1802ad8758'
    core_jar_sha256='c30996d9113c1584736e24b7bc4fbbba870857a60536f579f900a89c75f6df18'
    ;;
  5005)
    profile='cubism-5.0.05'
    editor_jar_sha256='1225893a466d486415773b0a62ec61a75badb2cb5cb2fce8ae744b0123becec4'
    core_jar_sha256='b0b855389d388ab3fc79d3e6dbaeba1a13659b57f8f629a8758a9bf007964594'
    ;;
  5006)
    profile='cubism-5.0.06'
    editor_jar_sha256='cbb9d9ecddb9be0252733ece055ecae7d709de5c56647b7226f6c44607e646f2'
    core_jar_sha256='c7d94635a3049912dd984d7f66420e686ef32bdcc4fe0279149e993041ff7854'
    ;;
  5100)
    profile='cubism-5.1.00'
    editor_jar_sha256='cdda37ed4105cf5e4ecb5811f7065df35b9b9a3319bcb74f8e9189d91e8d3f05'
    core_jar_sha256='0c67858efd261c2e5600ee8755b358d09deda5c63341a620aa64a6acbcce5de1'
    ;;
  5101)
    profile='cubism-5.1.01'
    editor_jar_sha256='ddb1f5569bc8da49f3c9f6b6eaec3fe57e4f7abec67c9c44079233ea51056c02'
    core_jar_sha256='6b1e530e62bc165bdb1e7677b1b5b953c3096aa635e56cc07ba57e2ecc4393bb'
    ;;
  5102)
    profile='cubism-5.1.02'
    editor_jar_sha256='0567130093015c969d32b91c6a48d4603c28c8224c6a14fa969491f8974f63ca'
    core_jar_sha256='16a54d40c5091e2a0d07c95332ce6ec8c03feb86c2e411b80a3e6d438b982f57'
    ;;
  5103)
    profile='cubism-5.1.03'
    editor_jar_sha256='a3d916077bd7a176345233da95f1eb0a8bbdf8967fd08162776d5bf6d746009b'
    core_jar_sha256='1af6619b951a87bb52a80173816179cab91836e696e753fb546d4c14505e7a92'
    ;;
  5200)
    profile='cubism-5.2.00'
    editor_jar_sha256='25c41c81ddb3af884b7f0d61a02eba9b09f0412bdc025997c419de7ab7158cf1'
    core_jar_sha256='9adfb1e98c0e427dc3f99790eba0c8c70c0195c0ac70efb2958c23bc22c96dab'
    ;;
  5201)
    profile='cubism-5.2.01'
    editor_jar_sha256='c1b70ca8c571c4f4df4ebce63b3381f6bed1b132ab0ed539046fd0609e2b00ac'
    core_jar_sha256='13388bb610b73a3e4df550418c16fc5fb94fb97f94c819dec5afda3fa5e97e00'
    ;;
  5202)
    profile='cubism-5.2.02'
    editor_jar_sha256='ba1f31a1846490c3f111a4ae99887c36bcc0c1bc1a3cf970eb5a7aeed7902aff'
    core_jar_sha256='78951b6a1bd975043b43fb067b040e3128fe9df071289a96d74c3253ba48f772'
    ;;
  5203)
    profile='cubism-5.2'
    editor_jar_sha256='bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd'
    core_jar_sha256='85959a0572be02ee45d128cfdaf9046631241310b741d6b149d295a0dec7451e'
    ;;
  5300)
    profile='cubism-5.3.00'
    editor_jar_sha256='c89dcaf17b6bfad7227db30bffc2f29cdf49b8e4b0cec1f12af2d85b1c13e767'
    core_jar_sha256='98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4'
    ;;
  5301)
    profile='cubism-5.3.01'
    editor_jar_sha256='694bb49c66ddcf15695dbd8f66e1cbaeb0097f651e2e77c327567244f801d2f4'
    core_jar_sha256='98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4'
    ;;
  5302)
    profile='cubism-5.3.02'
    editor_jar_sha256='988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21'
    core_jar_sha256='98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4'
    ;;
  5303)
    profile='cubism-5.3.03-distribution'
    editor_jar_sha256='bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166'
    core_jar_sha256='98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4'
    ;;
  5304)
    profile='cubism-5.3.04'
    editor_jar_sha256='c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d'
    core_jar_sha256='5458a64ebe33b38350de1437399343b0d502f2353a34a97da76d56d118c0d186'
    ;;
  *)
    echo "error: unsupported installed Core acquisition version: $version" >&2
    exit 2
    ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="${TURBOISM_WORKTREE_ID:-$(bash "$repo_root/scripts/dev/worktree-id.sh")}"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-core-acquisition-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

exec bash "$runner" \
  --name core-acquisition \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --aux-agent "$bundle_root/core-acquisition-probe-agent.jar:core-acquisition-probe-agent.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option "-Dturboism.coreAcquisition.profile=$profile" \
  --jvm-option "-Dturboism.coreAcquisition.expectedEditorSha256=$editor_jar_sha256" \
  --jvm-option "-Dturboism.coreAcquisition.expectedCoreSha256=$core_jar_sha256" \
  --jvm-option '-Dturboism.coreAcquisition.exitOnComplete=true' \
  --result-file 'state/core-acquisition-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --ready-timeout 300 \
  --result-timeout 480 \
  --exit-timeout 120 \
  "$@"
