#!/usr/bin/env bash
set -euo pipefail

fixture=${1:?usage: find-cubism-java-pid.sh FIXTURE_PATH}
ps -eo pid=,comm=,args= | awk -v fixture="$fixture" '
  $2 == "java.exe" {
    args = $0
    gsub(/\\/, "/", args)
    if (index(args, "Live2D_Cubism.jar") && index(args, fixture)) print $1
  }
'
