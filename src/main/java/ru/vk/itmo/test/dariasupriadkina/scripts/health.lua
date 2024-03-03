request = function()
    url = "/health"
    headers = {}
    headers["Host"] = "localhost:8080"
    return wrk.format("GET", url, headers)
end