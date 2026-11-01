#!/usr/bin/env python3
"""Generate the framework self-update activation resources for one agent build.

Public material only: an activation policy plus the framework signing *public* keyring. No network
access, no private keys, and disabling removes only the two files this command owns.
"""
from turboism_release.framework_client import main

if __name__ == "__main__":
    raise SystemExit(main())
