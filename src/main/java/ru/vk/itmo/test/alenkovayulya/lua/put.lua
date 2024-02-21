counter = 0

 function request()
     counter = counter + 1
     local headers = {}
     headers["Host"] = "localhost:8080"
     local key = "k" .. counter
     local value = "v$" .. counter
     return wrk.format("PUT", "/v0/entity?id=" .. key, headers, value)