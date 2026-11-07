# Architecture

The same blocking HTTP/1 core runs on JVM and Scala Native. Applications supply
configuration and a synchronous `Request => Response` callback.

```mermaid
flowchart TD
    App["Application"] -->|"Server.start(config)(handle)"| API["Server<br/>bound address and stop()"]
    API --> Engine["BlockingServer<br/>admission and shutdown ownership"]

    subgraph Core["Shared JVM / Scala Native implementation"]
        Engine --> Accept["Accept thread<br/>ServerSocket"]
        Client["HTTP client"] <-->|"TCP"| Accept
        Accept -->|"admit within maxConnections"| Worker["Connection worker<br/>one platform thread per connection"]
        Worker -->|"read next request head"| Parser["RequestHeadParser<br/>syntax, limits and framing"]
        Parser --> Exchange["Exchange<br/>one request / response lifetime"]
        Exchange --> Input["FixedLengthInputStream / ChunkedInputStream<br/>bounded request body and trailers"]
        Input --> Request["Request<br/>raw target, headers, body, addresses"]
        Exchange -->|"invoke with Request"| Handler["Application callback<br/>Request => Response"]
        Request -.-> Handler
        Handler --> Response["Response<br/>status, headers and body"]
        Response --> Writer["ResponseHeadWriter<br/>validate headers and select framing"]
        Writer --> Output["FixedLengthOutputStream / ChunkedOutputStream<br/>or connection-delimited output"]
        Output --> Socket["Buffered socket output"]
        Socket -->|"response bytes"| Client
        Engine --> Watcher["WriteTimeouts<br/>one watcher thread per server"]
        Watcher -.->|"close socket on expired write"| Socket
        Exchange -->|"complete body and persistence allow reuse"| Worker
    end
```

Connections run concurrently; exchanges on each connection run sequentially.
Admission has no accepted-socket task queue: excess connections are closed.
The parser enforces an absolute request-head deadline; body reads use socket
timeouts, while the watcher bounds socket writes. Handler computation has no
execution deadline.

The exchange owns stream lifetimes and protocol decisions, including lazy
`100 Continue`, bodyless responses and connection reuse. A response writer may
still consume the request body. Unread request bodies and terminal framing
failures prevent reuse; unread bodies are not drained.

`Server.stop()` stops admission, closes idle/head-reading connections, allows
active exchanges the configured shutdown grace, then closes remaining sockets.
It signals owned infrastructure and joins threads within the remaining budget.
Applications own when to call it; there is no automatic shutdown hook.

The public API is in `potateh/src/ba/sake/potateh`; connection, exchange and codec
implementation details are in its `internal` package. Routing, authentication,
TLS and application formats belong outside this core.
