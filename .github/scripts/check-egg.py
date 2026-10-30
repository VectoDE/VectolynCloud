"""Checks cloud/dist/pterodactyl/egg-vectolyn-cloud.json (operations.yml).

    python3 -I .github/scripts/check-egg.py cloud/dist/pterodactyl/egg-vectolyn-cloud.json

Checks the PTDL_v2 structure the panel imports, that every {{...}} in the startup command and in
config.files names an egg variable, that every default value passes its own rules, that the
setup file Wings writes only uses keys the setup file format knows (docs/cloud/setup.md), and that
the database mode "docker" cannot be chosen. Prints the setup.yml Wings would write with the
defaults. Standard library only.
"""

import json
import re
import sys

# Keys of the setup file (cloud/setup SetupFile, docs/cloud/setup.md), dot notation.
SETUP_KEYS = {
    "schema-version", "eula", "language", "network-name",
    "servers.proxy", "servers.lobby", "servers.survival", "servers.creative", "servers.mod",
    "servers.end", "servers.minigame", "servers.legacy-proxy", "servers.legacy-server",
    "network.bind", "network.java-port", "network.bedrock-port", "network.legacy-port",
    "database.mode", "database.host", "database.port", "database.name", "database.user",
    "database.password-file", "database.docker-image",
}
# Wings fills these itself.
WINGS_VALUES = {"server.build.default.port", "server.build.default.ip", "server.build.memory"}

problems = []


def check(condition, message):
    if not condition:
        problems.append(message)


def rule_problems(value, rules):
    """The subset of Laravel validation rules the egg uses."""
    found = []
    # Split exactly like the panel (Laravel) does: at every "|", also inside a regex. A regex with
    # "|" therefore breaks there and shows up as an unknown rule.
    for rule in rules.split("|"):
        name, _, arg = rule.partition(":")
        if name == "required" and value == "":
            found.append("required")
        elif name == "integer" and not re.fullmatch(r"-?[0-9]+", value):
            found.append("integer")
        elif name == "between":
            low, high = (int(x) for x in arg.split(","))
            if not re.fullmatch(r"-?[0-9]+", value) or not low <= int(value) <= high:
                found.append(rule)
        elif name == "in" and value not in arg.split(","):
            found.append(rule)
        elif name == "regex":
            pattern = arg[1:arg.rindex("/")]
            if not re.search(pattern, value):
                found.append(rule)
        elif name not in {"required", "string", "integer", "between", "in", "regex", "nullable"}:
            found.append("unknown rule " + name)
    return found


def wings_value(value):
    """Wings turns "true"/"false" into booleans and whole numbers into integers."""
    if value.lower() in ("true", "false"):
        return value.lower() == "true"
    if re.fullmatch(r"-?[0-9]+", value):
        return int(value)
    return value


def main(path):
    with open(path, encoding="utf-8") as f:
        egg = json.load(f)

    check(egg.get("meta", {}).get("version") == "PTDL_v2", "meta.version must be PTDL_v2")
    for key in ("name", "author", "description", "startup", "docker_images", "config", "scripts", "variables"):
        check(key in egg, f"missing {key}")
    check(isinstance(egg.get("docker_images"), dict) and egg["docker_images"], "docker_images is empty")
    for label, image in egg.get("docker_images", {}).items():
        check(image.startswith("ghcr.io/pterodactyl/yolks:java_"), f"docker image {label}: {image}")

    variables = {v["env_variable"]: v for v in egg["variables"]}
    check(len(variables) == len(egg["variables"]), "duplicate env_variable")
    for env, v in variables.items():
        for key in ("name", "description", "default_value", "user_viewable", "user_editable", "rules", "field_type"):
            check(key in v, f"variable {env}: missing {key}")
        check(isinstance(v.get("default_value"), str), f"variable {env}: default_value must be a string")
        check(re.fullmatch(r"[A-Z][A-Z0-9_]*", env) is not None, f"variable {env}: invalid name")
        for p in rule_problems(v["default_value"], v["rules"]):
            problems.append(f"variable {env}: default {v['default_value']!r} fails {p}")

    for ref in re.findall(r"\{\{([^}]+)\}\}", egg["startup"]):
        check(ref in variables, f"startup uses unknown variable {ref}")
    check("--non-interactive" in egg["startup"] and "--setup-file setup.yml" in egg["startup"],
          "startup must pass --non-interactive --setup-file setup.yml")
    check("--allow-root" not in egg["startup"], "startup must not pass --allow-root")

    config = egg["config"]
    files = json.loads(config["files"])
    startup = json.loads(config["startup"])
    json.loads(config["logs"])
    check(isinstance(startup.get("done"), str) and startup["done"], "config.startup.done is empty")
    check(isinstance(config.get("stop"), str) and config["stop"], "config.stop is empty")
    check(set(files) == {"setup.yml"}, "config.files must only write setup.yml")
    setup = files["setup.yml"]
    check(setup.get("parser") == "yaml", "setup.yml must use the yaml parser")

    rendered = {}
    for key, template in setup["find"].items():
        check(key in SETUP_KEYS, f"setup.yml key {key} is not part of the setup file format")
        value = template
        for ref in re.findall(r"\{\{([^}]+)\}\}", template):
            if ref.startswith("server.build.env."):
                env = ref[len("server.build.env."):]
                check(env in variables, f"setup.yml {key} uses unknown variable {env}")
                value = value.replace("{{" + ref + "}}", variables.get(env, {}).get("default_value", ""))
            else:
                check(ref in WINGS_VALUES, f"setup.yml {key} uses unknown Wings value {ref}")
                value = value.replace("{{" + ref + "}}", "25565")
        rendered[key] = wings_value(value)

    check(rendered.get("schema-version") == 1, "schema-version must become the integer 1")
    check(rendered.get("eula") is False, "the EULA must default to false (explicit consent)")
    for key in ("network.java-port", "network.bedrock-port", "network.legacy-port"):
        check(isinstance(rendered.get(key), int), f"{key} must become an integer")
    check(isinstance(rendered.get("servers.legacy-proxy"), bool), "servers.legacy-proxy must become a boolean")
    db_rules = variables.get("DB_MODE", {}).get("rules", "")
    check("docker" not in db_rules, "DB_MODE must not offer docker on Pterodactyl")

    install = egg["scripts"]["installation"]
    check(install.get("container", "").startswith("ghcr.io/pterodactyl/installers:"), "installer container")
    check("sha256sum" in install.get("script", ""), "installer must check the SHA-256")

    print("setup.yml with the default values (as Wings writes it):")
    for key in sorted(rendered):
        print(f"  {key}: {json.dumps(rendered[key])}")

    if problems:
        for p in problems:
            print(f"::error::egg: {p}")
        return 1
    print("Egg OK.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
