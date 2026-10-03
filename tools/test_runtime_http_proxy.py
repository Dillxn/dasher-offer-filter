import shlex
import unittest
from runtime_http_proxy import java_http_runtime, java_environment


class RuntimeProxyTest(unittest.TestCase):
    url = "https://dash-offer-filter-build.onrender.com/latest.json"

    def test_current_https_proxy_and_dynamic_port(self):
        args, env = java_http_runtime(self.url, {"HTTPS_PROXY": "http://127.0.0.1:37487"})
        self.assertIn("-Dhttps.proxyHost=127.0.0.1", args)
        self.assertIn("-Dhttps.proxyPort=37487", args)

    def test_lowercase_priority_and_http_protocol(self):
        args, _ = java_http_runtime("http://example.com", {"http_proxy": "http://new:8123", "HTTP_PROXY": "http://old:8889"})
        self.assertIn("-Dhttp.proxyHost=new", args)
        self.assertIn("-Dhttp.proxyPort=8123", args)
        args, _ = java_http_runtime(self.url, {"https_proxy": "", "HTTPS_PROXY": "http://old:8889"})
        self.assertNotIn("-Dhttps.proxyHost=old", args)

    def test_no_proxy_exact_domain_port_wildcard_and_case(self):
        for value in ("dash-offer-filter-build.onrender.com", ".onrender.com", "*", "ONRENDER.COM"):
            with self.subTest(value=value):
                args, _ = java_http_runtime(self.url, {"HTTPS_PROXY": "http://proxy:8123", "NO_PROXY": value})
                self.assertNotIn("-Dhttps.proxyHost=proxy", args)
        args, _ = java_http_runtime("https://example.com:443/path", {"https_proxy": "http://proxy:8123", "no_proxy": "example.com:443"})
        self.assertNotIn("-Dhttps.proxyHost=proxy", args)

    def test_nonmatching_no_proxy_retains_proxy(self):
        args, _ = java_http_runtime(self.url, {"https_proxy": "http://proxy:8123", "no_proxy": "other.example"})
        self.assertIn("-Dhttps.proxyHost=proxy", args)

    def test_no_proxy_environment_clears_inherited_routes(self):
        args, env = java_http_runtime(self.url, {"JAVA_TOOL_OPTIONS": "-Dhttps.proxyHost=stale -Dhttps.proxyPort=8889", "_JAVA_OPTIONS": "-DsocksProxyHost=stale"})
        self.assertNotIn("JAVA_TOOL_OPTIONS", env)
        self.assertNotIn("_JAVA_OPTIONS", env)
        self.assertIn("-Dhttps.proxyHost=", args)
        self.assertIn("-Djava.net.useSystemProxies=false", args)

    def test_stale_options_removed_but_tls_and_nonrouting_options_preserved(self):
        source = {"HTTPS_PROXY": "http://current:4567", "JAVA_TOOL_OPTIONS": '-Dhttps.proxyPort=8889 -Djavax.net.ssl.trustStore="/system/trust store" -Xmx1g',
                  "JDK_JAVA_OPTIONS": "-Dhttp.nonProxyHosts=*", "_JAVA_OPTIONS": "-Dhttps.proxyHost=stale -Djavax.net.ssl.trustStoreType=JKS"}
        args, env = java_http_runtime(self.url, source)
        self.assertIn("-Dhttps.proxyPort=4567", args)
        self.assertEqual(["-Djavax.net.ssl.trustStore=/system/trust store", "-Xmx1g"], shlex.split(env["JAVA_TOOL_OPTIONS"]))
        self.assertEqual(["-Djavax.net.ssl.trustStoreType=JKS"], shlex.split(env["_JAVA_OPTIONS"]))
        self.assertNotIn("JDK_JAVA_OPTIONS", env)
        self.assertIn("8889", source["JAVA_TOOL_OPTIONS"])

    def test_invalid_authenticated_or_unsupported_proxy_fails_without_values(self):
        for value in ("http://user:secret@proxy:8123", "https://proxy:8123", "socks5://proxy:8123", "proxy:8123",
                      "http://proxy:99999", "http://proxy:no", "http://proxy/path", "http://proxy/?secret", "http://"):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "Unsupported proxy configuration") as caught:
                java_http_runtime(self.url, {"HTTPS_PROXY": value})
            self.assertNotIn(value, str(caught.exception))

    def test_malformed_jvm_options_fail_without_echo(self):
        with self.assertRaisesRegex(ValueError, "Malformed inherited JVM options"):
            java_environment({"JAVA_TOOL_OPTIONS": '"secret'})


if __name__ == "__main__":
    unittest.main()
