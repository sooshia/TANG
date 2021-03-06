/**
 * Copyright (C) 2014 Microsoft Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
﻿using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Com.Microsoft.Tang.Annotations;

namespace Com.Microsoft.Tang.Examples
{
    public class DocumentedLocalNamedParameter
    {
        string value;
        [NamedParameter(Documentation = "doc stuff", ShortName = "DocFoo", DefaultValue = "some value")]
        sealed class Foo : Name<String> 
        {
        }

        [Inject]
        public DocumentedLocalNamedParameter([Parameter(Value = typeof(Foo))] String s) 
        {
            value = s;
        }

        public string ToString()
        {
            return value;
        }
    }
}
