"""Route local JVM HTTP probes through the current standard proxy environment.

Only routing properties are changed. TLS verification and trust-store settings
remain untouched. No endpoint, credential or environment value is logged.
"""
import os
import shlex
import urllib.parse
import urllib.request

ROUTING = {"http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort",
           "http.nonProxyHosts", "https.nonProxyHosts", "socksProxyHost", "socksProxyPort",
           "java.net.useSystemProxies"}


def java_environment(environment=None):
    env = dict(os.environ if environment is None else environment)
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        if name not in env:
            continue
        try:
            tokens = shlex.split(env[name])
        except ValueError:
            raise ValueError("Malformed inherited JVM options") from None
        kept = [token for token in tokens if not (
            token.startswith("-D") and token[2:].split("=", 1)[0] in ROUTING)]
        if kept:
            env[name] = shlex.join(kept)
        else:
            env.pop(name)
    return env


def java_http_runtime(url, environment=None):
    env = java_environment(environment)
    target = urllib.parse.urlsplit(url)
    if target.scheme not in ("http", "https") or not target.hostname or target.username is not None:
        raise ValueError("Invalid probe URL")
    args = ["-Djava.net.useSystemProxies=false", "-Dhttp.proxyHost=", "-Dhttps.proxyHost=",
            "-DsocksProxyHost=", "-Dhttp.nonProxyHosts=", "-Dhttps.nonProxyHosts="]
    bypass = env.get("no_proxy", env.get("NO_PROXY", ""))
    if urllib.request.proxy_bypass_environment(target.netloc, {"no": bypass}):
        return args, env
    key = target.scheme + "_proxy"
    value = env.get(key, env.get(key.upper(), ""))
    if not value:
        return args, env
    try:
        proxy = urllib.parse.urlsplit(value)
        port = proxy.port if proxy.port is not None else 80
        valid = (not any(char.isspace() or ord(char) < 32 or ord(char) == 127 for char in value)
                 and proxy.scheme == "http" and proxy.hostname and proxy.username is None
                 and proxy.password is None and proxy.path in ("", "/")
                 and not proxy.query and not proxy.fragment and 1 <= port <= 65535)
    except ValueError:
        valid = False
    if not valid:
        raise ValueError("Unsupported proxy configuration: use an HTTP proxy URL without credentials")
    # Java HTTPS uses HTTP CONNECT through these https.proxy* properties. An
    # https:// proxy transport/authenticated proxy needs support we do not guess.
    args.extend([f"-D{target.scheme}.proxyHost={proxy.hostname}", f"-D{target.scheme}.proxyPort={port}"])
    return args, env
