function request()
    headers = { }
    headers["Host"] = "localhost:8080"
    return wrk.format("GET", "/hello", headers)
end