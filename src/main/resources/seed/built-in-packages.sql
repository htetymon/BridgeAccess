-- BridgeAccess built-in catalogue: six published learning packages with real public sources,
-- content and a five-question quiz. One package has a contributor image and one uses a
-- YouTube video, so a fresh install exercises every package presentation.
--
-- This script is idempotent and never deletes existing rows. It is executed inside one
-- transaction by BuiltInPackageSeeder after the package image has been uploaded. Content and
-- questions are only inserted when their package row exists, so a duplicate resource link
-- contributed by a user cannot roll back the whole batch.

-- ------------------------------------------------------------- packages
insert into app.learning_packages
  (id, device_id, title, original_title, author, resource_link, resource_type, subject, package_size_bytes)
values
  ('b0000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-0000000000bb',
   'Renewable Energy Basics', 'Renewable energy explained', 'U.S. Energy Information Administration',
   'https://www.eia.gov/energyexplained/renewable-sources/', 'text', 'Science', 52000),
  ('b0000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-0000000000bb',
   'How Vaccines Train Your Immune System', 'Vaccine', 'Wikipedia contributors',
   'https://en.wikipedia.org/wiki/Vaccine', 'text', 'Health', 12400),
  ('b0000000-0000-4000-8000-000000000003', '00000000-0000-4000-8000-0000000000bb',
   'Understanding Fractions', 'Fractions', 'Khan Academy',
   'https://www.khanacademy.org/math/arithmetic/fraction-arithmetic', 'text', 'Math', 10200),
  ('b0000000-0000-4000-8000-000000000004', '00000000-0000-4000-8000-0000000000bb',
   'How the Internet Works', 'The Internet: Crash Course Computer Science #29', 'CrashCourse',
   'https://www.youtube.com/watch?v=AEaKrq3SpW8', 'video', 'Technology', 13100),
  ('b0000000-0000-4000-8000-000000000005', '00000000-0000-4000-8000-0000000000bb',
   'Everyday English Phrasal Verbs', 'English phrasal verbs', 'Wikipedia contributors',
   'https://en.wikipedia.org/wiki/English_phrasal_verbs', 'text', 'Language', 9700),
  ('b0000000-0000-4000-8000-000000000006', '00000000-0000-4000-8000-0000000000bb',
   'The Printing Press and the Spread of Ideas', 'Printing press', 'Wikipedia contributors',
   'https://en.wikipedia.org/wiki/Printing_press', 'text', 'History', 12900)
on conflict do nothing;

-- -------------------------------------------------------------- content
-- The app labels this learning step "Details". The column and API field are still called
-- summary for compatibility with offline bundles and saved progress. Built-in Details are
-- refreshed on startup as four longer teaching paragraphs; user-contributed packages are never
-- touched.
insert into app.package_content (package_id, overview, key_takeaways, summary, key_concepts)
select 'b0000000-0000-4000-8000-000000000001',
       'A beginner-friendly introduction to renewable energy: what it is, its main types, and why it matters.',
       array['Understand what renewable energy is',
             'Recognise the common types: solar, wind, hydro and geothermal',
             'Know the main benefits and limits',
             'See why it matters for a sustainable future'],
       'Renewable energy comes from sources that are naturally replenished, such as sunlight, wind, flowing water and heat inside the Earth. This makes it different from coal, oil and natural gas, which formed over a very long time and are used up when people burn them. Renewable does not mean that equipment lasts forever; it means the energy source is renewed on a human timescale. Many renewable systems make electricity with little or no greenhouse gas while operating, although building and maintaining equipment also use materials and energy.\n\nSolar panels convert sunlight into electricity. Wind turbines use moving air to turn blades connected to a generator. Hydropower captures energy from flowing or falling water, often at a dam. Geothermal systems use heat beneath the ground, for example to produce steam for electricity or to warm buildings directly. These methods all provide useful energy, but they work in different ways and depend on different local conditions. A sunny region may suit solar panels, while a windy coast may be better for wind power.\n\nSunlight and wind are variable: panels produce less at night or under heavy cloud, and wind turbines produce less when the air is still. Hydropower can depend on rainfall and water levels. Geothermal energy can provide steadier output, but suitable underground heat is not available everywhere. To keep electricity available, a power system can combine several sources, connect regions, adjust demand and store energy for later. Batteries are one way to store electricity, while other systems can store energy in different forms.\n\nRenewable energy can reduce the air pollution and greenhouse gas emissions caused by burning fossil fuels. Its benefits still need careful planning. Projects can require land, materials, transmission lines or changes to rivers, and costs vary by place and technology. The useful question is therefore not simply whether a source is renewable, but how well it fits local needs and what effects come with it. A mix of suitable sources can make the supply cleaner and more reliable.',
       '[{"term":"Renewable energy","explanation":"Energy from sources that naturally replenish, such as sun, wind and water."},
         {"term":"Solar power","explanation":"Electricity generated from sunlight, usually with photovoltaic panels."},
         {"term":"Wind power","explanation":"Electricity generated when wind turns the blades of a turbine."},
         {"term":"Hydropower","explanation":"Electricity generated by water flowing through turbines, often at a dam."},
         {"term":"Intermittency","explanation":"The way sun and wind output rises and falls, which storage helps smooth out."}]'::jsonb
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000001')
on conflict (package_id) do update set summary = excluded.summary;

insert into app.package_content (package_id, overview, key_takeaways, summary, key_concepts)
select 'b0000000-0000-4000-8000-000000000002',
       'How vaccines prepare the immune system to recognise and fight a disease before you meet it.',
       array['Know what an antigen and an antibody are',
             'Understand how vaccines create immune memory',
             'Explain why booster doses are sometimes needed',
             'Understand herd immunity'],
       'The immune system is the body''s defence against germs that can cause disease. It needs to recognise which things are harmful, because it meets many substances every day. One clue is an antigen: a molecule or marker associated with a germ. When the immune system recognises an antigen, it can make antibodies, which are proteins that attach to that particular target. This recognition takes time the first time the body meets a germ, so the person may become ill before the defence is ready.\n\nA vaccine prepares the immune system ahead of time. It presents a harmless form or part of an antigen, or instructions that let the body briefly make one germ protein. The vaccine is designed to teach recognition without giving the person the disease it protects against. The immune system responds by making antibodies and special memory cells. The exact vaccine ingredients and method differ, but the learning goal is similar: help the body practise recognising a target safely.\n\nIf the real germ enters the body later, memory cells can recognise its antigen and help the immune system respond more quickly. Antibodies can attach to the target, while other immune responses help remove the threat. This faster response can prevent illness or make it less severe. Some vaccines require more than one dose because a series can build a stronger response. A booster is an additional dose given later to refresh protection when immune memory has weakened or when guidance recommends another dose.\n\nVaccination can also affect how easily a disease spreads through a community. When many people are immune, the germ has fewer opportunities to pass from person to person. This indirect protection is often called herd immunity and can help people who cannot receive a particular vaccine. The level of community protection depends on the disease and the vaccine, so it is not identical in every situation. A vaccine is preventive: it trains the body before exposure and does not act as a treatment for an infection that is already underway.',
       '[{"term":"Antigen","explanation":"A molecule on a germ that the immune system learns to recognise."},
         {"term":"Antibody","explanation":"A protein made by the body that binds to a specific antigen."},
         {"term":"Memory cells","explanation":"Immune cells that remember an antigen and respond fast on a second meeting."},
         {"term":"Booster","explanation":"An extra vaccine dose that refreshes fading immune memory."},
         {"term":"Herd immunity","explanation":"Protection for a community when enough people are immune to stop spread."}]'::jsonb
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000002')
on conflict (package_id) do update set summary = excluded.summary;

insert into app.package_content (package_id, overview, key_takeaways, summary, key_concepts)
select 'b0000000-0000-4000-8000-000000000003',
       'A clear introduction to fractions: what they mean, how to compare them, and how to add them.',
       array['Read the numerator and denominator of a fraction',
             'Find equivalent fractions',
             'Add fractions with a common denominator'],
       'A fraction names an amount when a whole is divided into equal parts. It has two numbers separated by a line. The denominator is the bottom number and tells how many equal parts make the whole. The numerator is the top number and tells how many of those parts are being counted. For example, if a pizza is cut into four equal slices and you take three, the amount is three quarters, written 3/4. The parts must be equal for the fraction to describe a fair share of the whole.\n\nFractions can be written in different ways while keeping the same value. To make an equivalent fraction, multiply or divide both the numerator and denominator by the same nonzero number. For example, 1/2 becomes 2/4 when both numbers are multiplied by 2. It still means one half because the size of each part changes as the number of parts changes. To compare fractions, it helps to rewrite them with a common denominator. Then the pieces are the same size, so comparing the numerators shows which amount is larger.\n\nThe same idea helps when adding fractions. If the denominators already match, add the numerators and keep the denominator: 1/5 + 2/5 = 3/5. The denominator stays 5 because the fifths are the same size; together there are three of them. If the denominators differ, first rename the fractions as equivalent fractions with a shared denominator. For example, 1/2 is 3/6 and 1/3 is 2/6, so their sum is 5/6. Adding the denominators directly would combine unlike-sized parts and give the wrong result.\n\nA final answer can often be simplified. Find a number that divides both the numerator and denominator, then divide both by it. Since 6 and 8 are both divisible by 2, 6/8 becomes 3/4 without changing its value. The fraction is in simplest form when no whole number greater than 1 divides both numbers. Keeping the whole-number meaning in mind makes these steps easier: make equal-sized parts first, count them, and simplify only by changing both numbers together.',
       '[{"term":"Numerator","explanation":"The top number: how many parts you have."},
         {"term":"Denominator","explanation":"The bottom number: how many equal parts make the whole."},
         {"term":"Equivalent fractions","explanation":"Fractions with different numbers but the same value, like 1/2 and 2/4."},
         {"term":"Common denominator","explanation":"A shared bottom number that lets you add or compare fractions."},
         {"term":"Simplifying","explanation":"Dividing top and bottom by the same number to get the smallest equivalent fraction."}]'::jsonb
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000003')
on conflict (package_id) do update set summary = excluded.summary;

insert into app.package_content (package_id, overview, key_takeaways, summary, key_concepts)
select 'b0000000-0000-4000-8000-000000000004',
       'What happens between typing a web address and seeing the page: addresses, packets and servers.',
       array['Know what an IP address is',
             'Understand what DNS does',
             'Explain how data travels in packets',
             'Know the difference between a client and a server'],
       'The internet is a network made by connecting many smaller computer networks. A device needs an address so information can reach the right place. That address is called an IP address, and it works a little like a postal address. A website name is easier for people to remember than a string of numbers, so the internet uses the Domain Name System, or DNS, to look up the IP address linked to a name. This lookup is one of the first steps that happens when a browser opens a website.\n\nAfter the browser finds the address, it acts as a client and sends a request to a server. The server is another computer that stores or provides the requested page or service. The request asks for information; the server sends back a response that the browser can display. This exchange is not a single conversation between only two machines. Routers and other network equipment help move information across connected networks until it reaches its destination.\n\nLarge messages are divided into smaller pieces called packets. Each packet carries part of the data and information that helps it travel to the right place. Packets may take different routes through the network, depending on available connections. When they arrive, the receiving device puts them back into the correct order. Protocols such as TCP help devices check whether the data arrived completely and request missing parts again. Breaking information into packets allows networks to share capacity among many users instead of reserving one continuous path for each message.\n\nSecurity is another part of how web communication works. HTTPS uses encryption to make data harder for other people to read while it moves between a browser and a website. It does not make every online action safe by itself, so users and services still need good security practices. Together, names, addresses, requests, packets and protocols let computers made by different organisations communicate. Understanding these pieces helps explain why a web page may take time to load: the device is finding a server, exchanging requests and assembling the response over a network.',
       '[{"term":"IP address","explanation":"A number that identifies a device on a network."},
         {"term":"DNS","explanation":"The system that translates website names into IP addresses."},
         {"term":"Packet","explanation":"A small chunk of data sent across the network and reassembled at the end."},
         {"term":"Router","explanation":"A device that forwards packets towards their destination."},
         {"term":"Client and server","explanation":"The client requests data; the server stores and sends it."},
         {"term":"HTTPS","explanation":"The secure version of the web protocol, which encrypts traffic."}]'::jsonb
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000004')
on conflict (package_id) do update set summary = excluded.summary;

insert into app.package_content (package_id, overview, key_takeaways, summary, key_concepts)
select 'b0000000-0000-4000-8000-000000000005',
       'Common English phrasal verbs, how they are built, and how their meaning changes with the particle.',
       array['Know what a phrasal verb is',
             'Understand how the particle changes the meaning',
             'Use separable and inseparable phrasal verbs correctly',
             'Recognise common everyday examples'],
       'A phrasal verb combines a main verb with a short word called a particle. Common particles include up, off, out, on and in. The combined expression often has a meaning that is different from the verb by itself. For example, give can mean to hand something to another person, while give up means to stop trying. The particle is part of the expression, so it is useful to learn the verb and particle together rather than guess the meaning from the verb alone.\n\nSome phrasal verbs are literal and easier to picture. Sit down describes the action of moving into a seated position. Others are idiomatic, which means their meaning cannot be worked out exactly from each word. Give up does not mean to hand something upward; it means to stop trying. Context helps a reader decide which meaning is intended. The sentence around the phrasal verb often gives clues about the situation and the people involved.\n\nPhrasal verbs also follow patterns that affect where an object can go. With a separable phrasal verb such as turn off, the object may come after the whole expression or between the verb and particle: turn off the light or turn the light off. With an inseparable one such as look after, the object stays after the full expression: look after the children. The object cannot be placed between look and after. Learning whether a verb is separable or inseparable helps a sentence sound natural.\n\nPronouns make the separable pattern especially important. If the object is it, him or them, it usually goes between the verb and particle: turn it off, not turn off it. Phrasal verbs can have more than one meaning and grammar pattern, so examples are useful. Instead of memorising a long list, notice the expression in a complete sentence, check how it is used, and practise making a new sentence of your own. This connects meaning, word order and context in one small example.',
       '[{"term":"Phrasal verb","explanation":"A verb plus a particle that together form a new meaning."},
         {"term":"Particle","explanation":"The small word after the verb, such as up, off, out or on."},
         {"term":"Separable","explanation":"The object can go between the verb and the particle."},
         {"term":"Inseparable","explanation":"The object must come after the whole phrasal verb."},
         {"term":"Pronoun rule","explanation":"With separable verbs, a pronoun object must sit in the middle: turn it off."}]'::jsonb
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000005')
on conflict (package_id) do update set summary = excluded.summary;

insert into app.package_content (package_id, overview, key_takeaways, summary, key_concepts)
select 'b0000000-0000-4000-8000-000000000006',
       'How the printing press made books cheaper and helped new ideas spread across Europe.',
       array['Know when and where the printing press was developed',
             'Understand how movable type worked',
             'Explain how printing changed access to knowledge',
             'Connect printing to later historical changes'],
       'Before printing presses became common in Europe, books were usually copied by hand. A scribe had to write out each page, so making a book took a long time and required skilled work. Copies were therefore expensive and not widely available. Around 1440 in Mainz, Johannes Gutenberg developed a press that used movable metal type. The press did not create the idea of printing, but its reusable letters and practical method helped make printing books more efficiently in Europe.\n\nMovable type means that each letter is a separate piece. A printer arranges the pieces into lines and pages, applies ink, and presses them onto paper. After a page is printed, the letters can be taken apart and arranged again for another page. This reuse is a key difference from carving a complete page into one block. Once the type is set, the same page can be printed many times with less repeated handwork than copying every word by hand.\n\nPrinting made it possible to produce books and pamphlets in larger numbers and at lower cost. More copies could travel to different places, allowing readers to encounter the same text. As books became easier to obtain, reading and writing grew among more people. Scholars could compare their work, share discoveries and correct errors more easily. The press did not make everyone literate at once, but it helped knowledge circulate beyond the small groups who had access to handwritten books.\n\nPrinted texts also carried religious, political and scientific arguments. In the sixteenth century, Martin Luther''s writings spread widely and contributed to the Reformation. Later, printed books, newspapers and pamphlets supported education and public discussion. The effects depended on who could read, what was printed and who controlled publication; printing could spread inaccurate or persuasive claims as well as useful knowledge. Its lasting importance was the change in how quickly and widely written ideas could be reproduced and shared.',
       '[{"term":"Movable type","explanation":"Individual reusable letters arranged to print a page."},
         {"term":"Johannes Gutenberg","explanation":"The German craftsman who developed the printing press around 1440."},
         {"term":"Manuscript","explanation":"A book copied by hand, common before printing."},
         {"term":"Literacy","explanation":"The ability to read and write, which grew as books became cheaper."},
         {"term":"Reformation","explanation":"A 16th-century religious movement whose ideas spread rapidly through print."}]'::jsonb
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000006')
on conflict (package_id) do update set summary = excluded.summary;

-- ---------------------------------------------------------- quiz questions
insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000001', 1, 'What makes an energy source renewable?',
       array['It is cheap to use', 'It replenishes naturally', 'It is found underground', 'It burns cleanly'], 1,
       'Basics', 'Renewable sources such as sun and wind are naturally replenished and do not run out.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000001')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000001', 2, 'Which device turns sunlight directly into electricity?',
       array['Wind turbine', 'Dam', 'Photovoltaic panel', 'Steam boiler'], 2,
       'Types', 'Photovoltaic (solar) panels convert sunlight straight into electricity.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000001')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000001', 3, 'Which of these is NOT a renewable source?',
       array['Wind', 'Coal', 'Flowing water', 'Geothermal heat'], 1,
       'Types', 'Coal is a fossil fuel; it formed over millions of years and is not replenished.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000001')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000001', 4, 'What does "intermittency" refer to?',
       array['Output from sun and wind varies', 'Power lines losing energy', 'Plants shutting for repair', 'Fuel prices changing'], 0,
       'Challenges', 'Sun and wind do not produce a constant output, so grids need storage and a mix of sources.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000001')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000001', 5, 'What is a main benefit of renewable energy?',
       array['It never needs maintenance', 'It works the same everywhere', 'It produces little greenhouse gas', 'It needs no land'], 2,
       'Benefits', 'Generating power from renewables releases little or no greenhouse gas.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000001')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000002', 1, 'What is an antigen?',
       array['A medicine that kills germs', 'A molecule the immune system recognises', 'A type of blood cell', 'A vitamin'], 1,
       'Basics', 'Antigens are molecules on a germ that the immune system learns to recognise.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000002')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000002', 2, 'What does a vaccine show the immune system?',
       array['A harmless version of an antigen', 'A full-strength infection', 'Only vitamins', 'A copy of your own cells'], 0,
       'How it works', 'Vaccines present a harmless form of an antigen so the body can learn without getting ill.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000002')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000002', 3, 'Which cells let the body respond quickly to a germ it has met before?',
       array['Red blood cells', 'Skin cells', 'Memory cells', 'Nerve cells'], 2,
       'How it works', 'Memory cells remember an antigen and trigger a fast response on a second meeting.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000002')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000002', 4, 'Why are booster doses sometimes needed?',
       array['The first dose was fake', 'Immune memory can fade over time', 'Germs become vaccines', 'To cure an existing infection'], 1,
       'Boosters', 'A booster refreshes immune memory that has weakened since the first dose.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000002')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000002', 5, 'What is herd immunity?',
       array['Immunity found only in animals', 'Immunity from one dose', 'Protection when enough people are immune to stop spread', 'Immunity passed down in families'], 2,
       'Community', 'When most people are immune, a disease cannot spread easily, protecting others too.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000002')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000003', 1, 'In the fraction 3/4, what is the denominator?',
       array['3', '4', '7', '12'], 1,
       'Basics', 'The denominator is the bottom number: the whole is split into 4 equal parts.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000003')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000003', 2, 'Which fraction is equivalent to 1/2?',
       array['2/3', '3/4', '4/8', '1/4'], 2,
       'Equivalent fractions', 'Multiplying top and bottom of 1/2 by 4 gives 4/8, which has the same value.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000003')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000003', 3, 'What is 1/5 + 2/5?',
       array['3/10', '3/5', '2/25', '1/5'], 1,
       'Adding', 'With a common denominator, add the numerators and keep the denominator: 3/5.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000003')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000003', 4, 'What is the simplest form of 6/8?',
       array['3/4', '2/3', '6/8', '12/16'], 0,
       'Simplifying', 'Dividing top and bottom by their greatest common factor, 2, gives 3/4.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000003')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000003', 5, 'Before adding 1/2 and 1/3, what should you do first?',
       array['Add the denominators', 'Multiply the numerators', 'Rewrite them with a common denominator', 'Flip both fractions'], 2,
       'Adding', 'Rewrite as 3/6 and 2/6 first, then add to get 5/6.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000003')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000004', 1, 'What is an IP address?',
       array['A website name', 'A number that identifies a device on a network', 'A password', 'A type of cable'], 1,
       'Addresses', 'Every device on the internet has an IP address, like a postal address.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000004')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000004', 2, 'What does DNS do?',
       array['Encrypts web pages', 'Speeds up downloads', 'Translates website names into IP addresses', 'Blocks viruses'], 2,
       'Addresses', 'DNS turns a name like example.org into the IP address of the right server.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000004')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000004', 3, 'How does data travel across the internet?',
       array['As one large file', 'In small chunks called packets', 'Only by satellite', 'As radio sound'], 1,
       'Packets', 'Data is split into packets that may take different routes and are reassembled at the end.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000004')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000004', 4, 'What does a router do?',
       array['Stores websites', 'Forwards packets towards their destination', 'Types web addresses', 'Prints documents'], 1,
       'Network', 'Routers pass packets along the network towards where they need to go.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000004')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000004', 5, 'What does the "S" in HTTPS add?',
       array['Speed', 'Search', 'Security through encryption', 'Storage'], 2,
       'Security', 'HTTPS encrypts traffic so others cannot read what is sent.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000004')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000005', 1, 'What is a phrasal verb?',
       array['A verb in the past tense', 'A verb plus a particle with its own meaning', 'Two nouns joined together', 'A very long verb'], 1,
       'Basics', 'A phrasal verb combines a verb and a particle, often creating a new meaning.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000005')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000005', 2, 'What does "give up" mean?',
       array['To hand something over', 'To stop trying', 'To lift something', 'To give a gift'], 1,
       'Meaning', '"Give up" means to stop trying, which differs from "give" alone.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000005')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000005', 3, 'Which sentence is correct?',
       array['Turn off it.', 'Turn it off.', 'It turn off.', 'Off turn it.'], 1,
       'Grammar', 'With separable phrasal verbs, a pronoun object must go in the middle: "turn it off".'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000005')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000005', 4, '"Look after the children" uses which kind of phrasal verb?',
       array['Separable', 'Inseparable', 'Past tense', 'Passive'], 1,
       'Grammar', '"Look after" is inseparable: the object must come after the particle.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000005')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000005', 5, 'What is the best way to learn phrasal verbs?',
       array['Memorise alphabetical lists', 'Avoid using them', 'Learn them in whole sentences', 'Translate word by word'], 2,
       'Learning', 'Seeing phrasal verbs in context makes their meaning and grammar easier to remember.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000005')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000006', 1, 'Who developed the movable-type printing press in Europe around 1440?',
       array['Leonardo da Vinci', 'Johannes Gutenberg', 'Isaac Newton', 'Martin Luther'], 1,
       'People', 'Johannes Gutenberg developed the press in Mainz around 1440.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000006')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000006', 2, 'What was special about movable type?',
       array['Letters could be rearranged and reused', 'It printed in colour', 'It needed no ink', 'It wrote by hand'], 0,
       'Technology', 'Individual letters could be arranged into pages and reused for the next page.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000006')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000006', 3, 'How were most books made before the printing press?',
       array['By machine', 'Copied by hand', 'Carved in stone', 'Printed by computer'], 1,
       'Before print', 'Books were manuscripts, copied by hand, which made them slow and costly.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000006')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000006', 4, 'What was one effect of cheaper books?',
       array['Fewer people could read', 'Literacy grew', 'Libraries closed', 'Paper was banned'], 1,
       'Impact', 'Cheaper books gave more people access to reading, so literacy grew.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000006')
on conflict (package_id, position) do nothing;

insert into app.quiz_questions (package_id, position, question, options, correct_index, category, explanation)
select 'b0000000-0000-4000-8000-000000000006', 5, 'Which movement spread quickly thanks to printed pamphlets?',
       array['The Industrial Revolution', 'The Reformation', 'The Space Race', 'The Renaissance in China'], 1,
       'Impact', 'Reformation ideas spread rapidly across Europe through printed material.'
where exists (select 1 from app.learning_packages where id = 'b0000000-0000-4000-8000-000000000006')
on conflict (package_id, position) do nothing;

-- --------------------------------------------------------------- publish
update app.learning_packages lp
   set status = 'published',
       published_at = v.published_at
  from (values
    ('b0000000-0000-4000-8000-000000000006'::uuid, timestamptz '2026-11-15 09:00:00+00'),
    ('b0000000-0000-4000-8000-000000000005'::uuid, timestamptz '2026-11-13 14:30:00+00'),
    ('b0000000-0000-4000-8000-000000000004'::uuid, timestamptz '2026-11-11 08:15:00+00'),
    ('b0000000-0000-4000-8000-000000000003'::uuid, timestamptz '2026-11-09 16:45:00+00'),
    ('b0000000-0000-4000-8000-000000000002'::uuid, timestamptz '2026-11-07 11:20:00+00'),
    ('b0000000-0000-4000-8000-000000000001'::uuid, timestamptz '2026-11-05 10:00:00+00')
  ) as v(id, published_at)
 where lp.id = v.id
   and lp.status = 'processing';
