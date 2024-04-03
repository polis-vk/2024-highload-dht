local threads = {}

function setup(thread)
   table.insert(threads, thread)
end

function init(args)
   count_404 = 0
   count_503 = 0
end

function request()
     key = math.random(17500000)

     return wrk.format("GET", "/v0/entity?id=k" .. key)
 end

function response(status, headers, body)
    local nstatus = tonumber(status)
    if nstatus == 404 then count_404 = count_404 + 1
    elseif nstatus == 503 then count_503 = count_503 + 1
    end
end

function done(summary, latency, requests)
   total_404 = 0
   total_503 = 0

   for index, thread in ipairs(threads) do
      total_404 = total_404 + thread:get("count_404")
      total_503 = total_503 + thread:get("count_503")
   end

   print("------------------------------\n")
   local msg_status = "HTTP Status %s Count: %d"
   print(msg_status:format("404", total_404))
   print(msg_status:format("503", total_503))
end